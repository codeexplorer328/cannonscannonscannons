# turns the bbmodels into item models and the bone table for CannonType.java

import argparse
import base64
import json
import math
import os
import sys
from collections import OrderedDict

LEGAL_ANGLES = {-45.0, -22.5, 0.0, 22.5, 45.0}
EPS = 1e-6
JAVA_MIN, JAVA_MAX = -16.0, 32.0
AXES = ['x', 'y', 'z']

def near(a, b): return abs(a - b) < 1e-4

def classify_rotation(rot):
    # returns the axis and angle if java can do it
    if not rot or all(abs(v) < EPS for v in rot):
        return ('y', 0.0)
    nz = [i for i, v in enumerate(rot) if abs(v) > EPS]
    if len(nz) != 1:
        return None
    ax, ang = nz[0], rot[nz[0]]
    if not any(near(ang, L) for L in LEGAL_ANGLES):
        return None
    snapped = min(LEGAL_ANGLES, key=lambda L: abs(L - ang))
    return (AXES[ax], snapped)

def load(path):
    with open(path) as f:
        return json.load(f)

def build_index(model):
    elems = {e['uuid']: e for e in model['elements']}
    groups = {g['uuid']: g for g in model['groups']}
    return elems, groups

def walk(node, elems, groups, chain, out):
    # flattens the outliner
    if isinstance(node, str):
        if node in elems:
            out.append((elems[node], list(chain)))
        return
    g = groups.get(node['uuid'])
    if g is not None:
        chain = chain + [g]
    for child in node.get('children', []):
        walk(child, elems, groups, chain, out)

def accumulated_pivot(chain):
    # bone pivot in model space
    if not chain:
        return [0.0, 0.0, 0.0]
    return list(chain[-1].get('origin', [0, 0, 0]))

def _rot_axis(axis, deg, v):
    r = math.radians(deg); c, s = math.cos(r), math.sin(r)
    x, y, z = v
    if axis == 0: return [x, y*c - z*s, y*s + z*c]
    if axis == 1: return [x*c + z*s, y, -x*s + z*c]
    return [x*c - y*s, x*s + y*c, z]

def _rot_zyx(euler, v):
    # blockbench rotates x then y then z
    for axis in (0, 1, 2):
        if abs(euler[axis]) > EPS:
            v = _rot_axis(axis, euler[axis], v)
    return v

def world_point(point, chain):
    # rotates a point through every parent group above it
    p = list(point)
    for g in reversed(chain):
        gr = g.get('rotation') or [0, 0, 0]
        if any(abs(v) > EPS for v in gr):
            o = g.get('origin', [0, 0, 0])
            p = _rot_zyx(gr, [p[i] - o[i] for i in range(3)])
            p = [p[i] + o[i] for i in range(3)]
    return p

def accumulated_rotation(chain):
    r = [0.0, 0.0, 0.0]
    for g in chain:
        gr = g.get('rotation') or [0, 0, 0]
        for i in range(3):
            r[i] += gr[i]
    return r

OPPOSITE = {'north': 'south', 'south': 'north', 'east': 'west', 'west': 'east'}

def prerotate_180_y(je):
    # item displays render everything turned 180 so turn it back here
    f, t = je['from'], je['to']
    out = {'from': [round(16 - t[0], 5), f[1], round(16 - t[2], 5)],
           'to':   [round(16 - f[0], 5), t[1], round(16 - f[2], 5)],
           'faces': {}}
    if 'rotation' in je:
        r = je['rotation']; o = r['origin']
        out['rotation'] = {'origin': [round(16 - o[0], 5), o[1], round(16 - o[2], 5)],
                           'axis': r['axis'],
                           'angle': r['angle'] if r['axis'] == 'y' else -r['angle']}
    for face, fd in je['faces'].items():
        if face in OPPOSITE:
            out['faces'][OPPOSITE[face]] = fd
        else:  # top and bottom faces just get their texture turned
            u = fd['uv']
            out['faces'][face] = {**fd, 'uv': [u[2], u[3], u[0], u[1]]}
    return out

def convert(path, out_dir, namespace, model_id):
    model = load(path)
    elems, groups = build_index(model)
    textures = model['textures']

    # has to be item not entity or the textures never make it into the atlas
    os.makedirs(f"{out_dir}/assets/{namespace}/textures/item/{model_id}", exist_ok=True)
    os.makedirs(f"{out_dir}/assets/{namespace}/models/entity/{model_id}", exist_ok=True)
    os.makedirs(f"{out_dir}/assets/{namespace}/items", exist_ok=True)

    # pull out the textures
    tex_names = []
    for i, t in enumerate(textures):
        raw = t['source'].split('base64,', 1)[1]
        safe = t['name'].replace('.png', '').lower()
        safe = ''.join(c if c.isalnum() or c == '_' else '_' for c in safe)
        safe = f"{i}_{safe}"
        tex_names.append(safe)
        with open(f"{out_dir}/assets/{namespace}/textures/item/{model_id}/{safe}.png", 'wb') as f:
            f.write(base64.b64decode(raw))

    flat = []
    for node in model['outliner']:
        walk(node, elems, groups, [], flat)

    # sort the cubes into bones
    bones = OrderedDict()
    spill = 0
    for el, chain in flat:
        legal = classify_rotation(el.get('rotation'))
        if legal is not None:
            key = 'bone_' + ('root' if not chain else chain[-1]['name'])
            local = accumulated_pivot(chain)
            pivot = world_point(local, chain[:-1]) if chain else local
            brot = accumulated_rotation(chain)
            parent = ('bone_' + chain[-2]['name']) if len(chain) > 1 else None
        else:
            spill += 1
            key = f'bone_free{spill}'
            local = list(el.get('origin', [0, 0, 0]))
            pivot = world_point(local, chain)
            parent = ('bone_' + chain[-1]['name']) if chain else None
            brot = [a + b for a, b in zip(accumulated_rotation(chain), el['rotation'])]
            # adding angles only works when they share an axis
            axes = {i for g in chain for i, v in enumerate(g.get('rotation') or [0,0,0]) if abs(v) > EPS}
            axes |= {i for i, v in enumerate(el['rotation']) if abs(v) > EPS}
            if len(axes) > 1 and any(abs(v) > EPS for g in chain for v in (g.get('rotation') or [0,0,0])):
                print(f"  WARNING {el.get('name')}: multi-axis rotation under a rotated parent, "
                      f"euler sum is approximate", file=sys.stderr)
        b = bones.setdefault(key, {'local': local, 'pivot': pivot, 'rotation': brot, 'parent': parent, 'elements': []})
        b['elements'].append((el, legal))

    # write one model per bone
    manifest = {'namespace': namespace, 'model_id': model_id, 'bones': []}
    for name, b in bones.items():
        px, py, pz = b['pivot']
        # check it still fits
        lo = [1e9] * 3; hi = [-1e9] * 3
        for el, _ in b['elements']:
            inf = el.get('inflate') or 0.0
            f = [el['from'][i] - inf for i in range(3)]
            t = [el['to'][i] + inf for i in range(3)]
            for i in range(3):
                lo[i] = min(lo[i], f[i] - b['local'][i] + 8)
                hi[i] = max(hi[i], t[i] - b['local'][i] + 8)
        scale = 1.0
        while any(v < JAVA_MIN for v in lo) or any(v > JAVA_MAX for v in hi):
            scale *= 0.5
            lo = [(v - 8) * 0.5 + 8 for v in lo]
            hi = [(v - 8) * 0.5 + 8 for v in hi]
            if scale < 0.03:
                raise SystemExit(f"{name}: cannot fit in Java element range")

        used_tex, jelems = set(), []
        for el, legal in b['elements']:
            inf = el.get('inflate') or 0.0
            f, t = [], []
            for i in range(3):
                a = (el['from'][i] - inf - b['local'][i]) * scale + 8
                z = (el['to'][i] + inf - b['local'][i]) * scale + 8
                f.append(round(a, 5)); t.append(round(z, 5))
            je = {'from': f, 'to': t, 'faces': {}}
            # split out cubes get rotated by the display instead
            axis, ang = legal if legal is not None else ('y', 0.0)
            if abs(ang) > EPS:
                org = [round((el['origin'][i] - b['local'][i]) * scale + 8, 5) for i in range(3)]
                je['rotation'] = {'origin': org, 'axis': axis, 'angle': ang}
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
                je['faces'][fname] = {
                    'uv': [round(u[0] / uw * 16, 5), round(u[1] / uh * 16, 5),
                           round(u[2] / uw * 16, 5), round(u[3] / uh * 16, 5)],
                    'texture': f'#tex{ti}',
                }
                used_tex.add(ti)
            jelems.append(prerotate_180_y(je))

        jmodel = {
            'parent': 'minecraft:block/block',
            'textures': {f'tex{i}': f'{namespace}:item/{model_id}/{tex_names[i]}'
                         for i in sorted(used_tex)},
            'elements': jelems,
            'display': {'head': {'translation': [0, 0, 0], 'scale': [1, 1, 1]}},
        }
        jmodel['textures']['particle'] = jmodel['textures'][f'tex{sorted(used_tex)[0]}']

        fname = f'{model_id}_{name}'
        with open(f"{out_dir}/assets/{namespace}/models/entity/{model_id}/{name}.json", 'w') as fh:
            json.dump(jmodel, fh, indent=2)
        with open(f"{out_dir}/assets/{namespace}/items/{fname}.json", 'w') as fh:
            json.dump({'model': {'type': 'minecraft:model',
                                 'model': f'{namespace}:entity/{model_id}/{name}'}}, fh, indent=2)

        manifest['bones'].append({
            'name': name,
            'item_model': f'{namespace}:{fname}',
            # pixels to blocks
            'translation': [round(px / 16, 6), round(py / 16, 6), round(pz / 16, 6)],
            'rotation_euler_deg': [round(v, 5) for v in b['rotation']],
            'scale': round(1.0 / scale, 6),
            'parent': b['parent'],
            'element_count': len(jelems),
        })

    with open(f"{out_dir}/{model_id}_bones.json", 'w') as fh:
        json.dump(manifest, fh, indent=2)
    return manifest

MODELS = [('cannon_V4.json', 'cannon'),
          ('makeshift_cannon_V4.json', 'makeshift_cannon')]

if __name__ == '__main__':
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('src', nargs='?', default=os.path.join(here, 'models'),
                    help='folder holding the .bbmodel files (renamed .json)')
    ap.add_argument('out', nargs='?',
                    default=os.path.normpath(os.path.join(here, '..', 'resourcepack', 'src')),
                    help='resource pack root to write into')
    ap.add_argument('--namespace', default='cannons')
    args = ap.parse_args()

    for filename, mid in MODELS:
        path = os.path.join(args.src, filename)
        if not os.path.exists(path):
            sys.exit(f"missing {path}")
        m = convert(path, args.out, args.namespace, mid)
        print(f"\n=== {mid}: {len(m['bones'])} bones ===")
        for b in m['bones']:
            print(f"  {b['name']:<16} elems={b['element_count']:<3} "
                  f"scale={b['scale']:<5} rot={b['rotation_euler_deg']} "
                  f"trans={b['translation']}")
