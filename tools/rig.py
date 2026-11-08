# animated bbmodel to item models and rig

import argparse
import base64
import json
import os
import sys

from convert import classify_rotation, prerotate_180_y, _rot_axis, EPS, JAVA_MIN, JAVA_MAX

NORMALS = {'north': (0, 0, -1), 'south': (0, 0, 1), 'west': (-1, 0, 0),
           'east': (1, 0, 0), 'down': (0, -1, 0), 'up': (0, 1, 0)}


def num(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return 0.0


def fit(lo, hi):
    k = 1.0
    while True:
        span = [(hi[i] - lo[i]) * k for i in range(3)]
        if all(s <= JAVA_MAX - JAVA_MIN for s in span):
            break
        k *= 0.5
    shift = []
    for i in range(3):
        a, b = (lo[i]) * k + 8, (hi[i]) * k + 8
        if a >= JAVA_MIN and b <= JAVA_MAX:
            shift.append(0.0)
        else:
            shift.append(round(8 - (a + b) / 2, 5))
    return k, shift


def face_st(face, f, t, p):
    def frac(i):
        return (p[i] - f[i]) / (t[i] - f[i]) if t[i] != f[i] else 0.5
    fx, fy, fz = frac(0), frac(1), frac(2)
    return {'north': (1 - fx, 1 - fy), 'south': (fx, 1 - fy), 'east': (1 - fz, 1 - fy),
            'west': (fz, 1 - fy), 'up': (fx, fz), 'down': (fx, 1 - fz)}[face]


def face_uv(uv, rot, s, t):
    corners = []
    for k in range(4):
        j = (k + int(rot) // 90) % 4
        corners.append((uv[0] if j in (0, 1) else uv[2], uv[1] if j in (0, 3) else uv[3]))
    w = [(1 - s) * (1 - t), (1 - s) * t, s * t, s * (1 - t)]
    return (sum(w[i] * corners[i][0] for i in range(4)), sum(w[i] * corners[i][1] for i in range(4)))


def face_samples(face, f, t):
    ax = {'north': (2, 0), 'south': (2, 1), 'west': (0, 0), 'east': (0, 1), 'down': (1, 0), 'up': (1, 1)}[face]
    others = [i for i in range(3) if i != ax[0]]
    out = []
    for a, b in ((0.2, 0.3), (0.8, 0.35), (0.4, 0.9)):
        q = [0.0, 0.0, 0.0]
        q[ax[0]] = t[ax[0]] if ax[1] else f[ax[0]]
        q[others[0]] = f[others[0]] + (t[others[0]] - f[others[0]]) * a
        q[others[1]] = f[others[1]] + (t[others[1]] - f[others[1]]) * b
        out.append(q)
    return out


def bake_quarter_turns(el):
    rot = el.get('rotation') or [0, 0, 0]
    axes = [i for i in range(3) if abs(rot[i]) > EPS]
    if len(axes) != 1:
        return el
    ax = axes[0]
    k = int(round(rot[ax] / 90.0))
    rem = rot[ax] - 90 * k
    if k % 4 == 0 or classify_rotation([rem if i == ax else 0 for i in range(3)]) is None:
        return el
    deg = 90.0 * k
    o = el['origin']

    def turn(v):
        r = _rot_axis(ax, deg, [v[i] - o[i] for i in range(3)])
        return [round(r[i] + o[i], 6) for i in range(3)]

    corners = [turn([x, y, z]) for x in (el['from'][0], el['to'][0])
               for y in (el['from'][1], el['to'][1]) for z in (el['from'][2], el['to'][2])]
    nf = [min(c[i] for c in corners) for i in range(3)]
    nt = [max(c[i] for c in corners) for i in range(3)]

    faces = {}
    for name, fd in (el.get('faces') or {}).items():
        tip = turn([o[i] + NORMALS[name][i] for i in range(3)])
        n = tuple(int(round(tip[i] - o[i])) for i in range(3))
        new = [k2 for k2, v in NORMALS.items() if v == n][0]
        face_rot = int(fd.get('rotation', 0) or 0)
        pick = None
        for r in (0, 90, 180, 270):
            ok = True
            for q in face_samples(name, el['from'], el['to']):
                a = face_uv(fd['uv'], face_rot, *face_st(name, el['from'], el['to'], q))
                b = face_uv(fd['uv'], r, *face_st(new, nf, nt, turn(q)))
                if abs(a[0] - b[0]) > 1e-6 or abs(a[1] - b[1]) > 1e-6:
                    ok = False
                    break
            if ok:
                pick = r
                break
        if pick is None:
            raise SystemExit(f"could not remap face {name} when baking a turn")
        faces[new] = {**fd, 'rotation': pick}
    out = dict(el)
    out['from'], out['to'], out['faces'] = nf, nt, faces
    out['rotation'] = [rem if i == ax else 0.0 for i in range(3)]
    return out


def face_uvs(el, textures, used):
    faces = {}
    for fname, fd in (el.get('faces') or {}).items():
        if not isinstance(fd, dict) or 'uv' not in fd:
            continue
        ti = fd.get('texture')
        if ti is None or ti is False:
            continue
        tex = textures[ti]
        uw = tex.get('uv_width') or tex['width']
        uh = tex.get('uv_height') or tex['height']
        u = fd['uv']
        faces[fname] = {'uv': [round(u[0] / uw * 16, 5), round(u[1] / uh * 16, 5),
                               round(u[2] / uw * 16, 5), round(u[3] / uh * 16, 5)],
                        'texture': f'#tex{ti}'}
        r = int(fd.get('rotation', 0) or 0) % 360
        if r:
            faces[fname]['rotation'] = r
        used.add(ti)
    return faces


def build(path, out_dir, namespace, model_id):
    bb = json.load(open(path))
    textures = bb['textures']
    elems = {e['uuid']: e for e in bb['elements']}
    groups = {g['uuid']: g for g in bb['groups']}

    bones, pieces = [], {}

    seen = set()

    def visit(node, parent):
        g = groups[node['uuid']]
        idx = len(bones)
        bones.append({'name': g['name'], 'parent': parent,
                      'origin': [float(v) for v in g.get('origin', [0, 0, 0])],
                      'rot': [float(v) for v in (g.get('rotation') or [0, 0, 0])]})
        for child in node.get('children', []):
            if isinstance(child, str):
                el = elems.get(child)
                if el is None or el.get('export') is False:
                    continue
                sig = json.dumps([idx] + [el.get(k) for k in ('from', 'to', 'rotation', 'origin', 'inflate', 'faces')], sort_keys=True)
                if sig in seen:
                    continue
                seen.add(sig)
                el = bake_quarter_turns(el)
                legal = classify_rotation(el.get('rotation'))
                if legal is not None:
                    key = (idx, None, None)
                else:
                    o = tuple(round(float(v), 4) for v in el['origin'])
                    r = tuple(round(float(v), 4) for v in el['rotation'])
                    key = (idx, o, r)
                pieces.setdefault(key, []).append((el, legal))
            else:
                visit(child, idx)

    for node in bb['outliner']:
        if isinstance(node, str):
            continue
        visit(node, -1)

    tex_dir = f"{out_dir}/assets/{namespace}/textures/item/{model_id}"
    mdl_dir = f"{out_dir}/assets/{namespace}/models/entity/{model_id}"
    itm_dir = f"{out_dir}/assets/{namespace}/items"
    for d in (tex_dir, mdl_dir, itm_dir):
        os.makedirs(d, exist_ok=True)

    tex_names = {}
    rig_pieces = []
    used_all = set()
    count = {}
    for (bi, o, r), members in pieces.items():
        bone = bones[bi]
        origin = list(o) if o is not None else bone['origin']
        offset = [origin[i] - bone['origin'][i] for i in range(3)]
        rot = list(r) if r is not None else [0.0, 0.0, 0.0]

        lo, hi = [1e9] * 3, [-1e9] * 3
        for el, _ in members:
            inf = el.get('inflate') or 0.0
            for i in range(3):
                lo[i] = min(lo[i], el['from'][i] - inf - origin[i])
                hi[i] = max(hi[i], el['to'][i] + inf - origin[i])
        k, shift = fit(lo, hi)

        used = set()
        jelems = []
        for el, legal in members:
            inf = el.get('inflate') or 0.0
            f = [round((el['from'][i] - inf - origin[i]) * k + 8 + shift[i], 5) for i in range(3)]
            t = [round((el['to'][i] + inf - origin[i]) * k + 8 + shift[i], 5) for i in range(3)]
            je = {'from': f, 'to': t, 'faces': face_uvs(el, textures, used)}
            if legal is not None and abs(legal[1]) > EPS:
                org = [round((el['origin'][i] - origin[i]) * k + 8 + shift[i], 5) for i in range(3)]
                je['rotation'] = {'origin': org, 'axis': legal[0], 'angle': legal[1]}
            jelems.append(prerotate_180_y(je))
        used_all |= used

        n = count.get(bone['name'], 0)
        count[bone['name']] = n + 1
        pname = bone['name'] if n == 0 else f"{bone['name']}_{n}"
        jmodel = {'parent': 'minecraft:block/block',
                  'textures': {f'tex{i}': f'{namespace}:item/{model_id}/{{T{i}}}' for i in sorted(used)},
                  'elements': jelems}
        rig_pieces.append({'name': pname, 'bone': bi,
                           'model': f'{namespace}:{model_id}_{pname}',
                           'offset': [round(v, 5) for v in offset],
                           'rot': rot, 'shift': shift, 'k': k,
                           '_json': jmodel})

    for i in sorted(used_all):
        t = textures[i]
        safe = ''.join(c if c.isalnum() or c == '_' else '_' for c in t['name'].replace('.png', '').lower())
        tex_names[i] = f"{i}_{safe}"
        raw = t['source'].split('base64,', 1)[1]
        with open(f"{tex_dir}/{tex_names[i]}.png", 'wb') as fh:
            fh.write(base64.b64decode(raw))

    for p in rig_pieces:
        jm = p.pop('_json')
        for key, val in jm['textures'].items():
            i = int(key[3:])
            jm['textures'][key] = val.replace(f'{{T{i}}}', tex_names[i])
        first = sorted(jm['textures'])[0]
        jm['textures']['particle'] = jm['textures'][first]
        with open(f"{mdl_dir}/{p['name']}.json", 'w') as fh:
            json.dump(jm, fh, indent=2)
        with open(f"{itm_dir}/{model_id}_{p['name']}.json", 'w') as fh:
            json.dump({'model': {'type': 'minecraft:model',
                                 'model': f'{namespace}:entity/{model_id}/{p["name"]}'}}, fh, indent=2)

    anims = {}
    by_uuid = {g['uuid']: i for i, g in enumerate(bb['groups'])}
    name_to_bone = {b['name']: i for i, b in enumerate(bones)}
    for a in bb.get('animations', []):
        channels = {}
        for uuid, an in (a.get('animators') or {}).items():
            kfs = an.get('keyframes') or []
            if not kfs:
                continue
            bname = an.get('name')
            if bname not in name_to_bone:
                continue
            ch = channels.setdefault(bname, {})
            for kf in kfs:
                dp = kf['data_points'][0]
                ch.setdefault(kf['channel'], []).append(
                    [round(float(kf['time']), 5), num(dp.get('x')), num(dp.get('y')), num(dp.get('z'))])
            for lst in ch.values():
                lst.sort(key=lambda r: r[0])
        anims[a['name']] = {'length': a.get('length', 0), 'bones': channels}

    rig = {'model': model_id, 'bones': bones, 'pieces': rig_pieces, 'animations': anims}
    return rig


if __name__ == '__main__':
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser()
    ap.add_argument('model')
    ap.add_argument('id')
    ap.add_argument('pack')
    ap.add_argument('rig_out')
    ap.add_argument('--namespace', default='cannons')
    args = ap.parse_args()
    if not os.path.exists(args.model):
        sys.exit(f"missing {args.model}")
    rig = build(args.model, args.pack, args.namespace, args.id)
    with open(args.rig_out, 'w') as fh:
        json.dump(rig, fh, indent=1)
    print(f"{args.id}: {len(rig['bones'])} bones {len(rig['pieces'])} pieces "
          f"{len(rig['animations'])} animations")
    for p in rig['pieces']:
        print(f"  {p['name']:<10} bone={rig['bones'][p['bone']]['name']:<7} k={p['k']} shift={p['shift']} rot={p['rot']}")
