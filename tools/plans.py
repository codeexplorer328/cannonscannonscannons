# plan icons and desk variants after rig.py on build_zone

import json
import os
import sys

from PIL import Image

PAPER = (0xbc, 0xc9, 0xda, 255)

INK = {
    'x': (0x3f, 0x59, 0x78, 255),
    'o': (0x40, 0x50, 0x63, 255),
    '#': (0x24, 0x37, 0x4d, 255),
    '.': PAPER,
}

GLYPHS = {
    'cannon': [
        '.xxxo.',
        '.x..o.',
        '.x..#.',
        'xooooo',
        'x....o',
        'oo####',
    ],
    'makeshift_cannon': [
        '.xxxo.',
        '.x..o.',
        '.o..o.',
        '.o..#.',
        '.o..#.',
        '.####.',
    ],
    'ballista': [
        '..xo..',
        '.xxoo.',
        'xxxoo#',
        'x.xo.#',
        '..xo..',
        '.x##o.',
    ],
    'mounted_ballista': [
        'x.xo.#',
        'x.xo.#',
        'xxxoo#',
        'x.xo.#',
        '..xo..',
        '.x##o.',
    ],
}

REGION = (16, 18, 32, 34)


def icon(base, rows):
    img = base.crop(REGION).rotate(180)
    for r, row in enumerate(rows):
        for c, ch in enumerate(row):
            img.putpixel((5 + c, 7 + r), INK[ch])
    return img


def write_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w') as f:
        json.dump(data, f, indent=2)


def main(pack):
    ns = os.path.join(pack, 'assets', 'cannons')
    base = Image.open(os.path.join(ns, 'textures/item/build_zone/0_texture.png')).convert('RGBA')
    desk = json.load(open(os.path.join(ns, 'models/entity/build_zone/bone2.json')))

    for kind, rows in GLYPHS.items():
        pic = icon(base, rows)
        os.makedirs(os.path.join(ns, 'textures/item/build_plan'), exist_ok=True)
        pic.save(os.path.join(ns, f'textures/item/build_plan/{kind}.png'))
        write_json(os.path.join(ns, f'models/item/build_plan/{kind}.json'), {
            'parent': 'minecraft:item/generated',
            'textures': {'layer0': f'cannons:item/build_plan/{kind}'},
        })
        write_json(os.path.join(ns, f'items/build_plan_{kind}.json'), {
            'model': {'type': 'minecraft:model', 'model': f'cannons:item/build_plan/{kind}'},
        })

        sheet = base.copy()
        sheet.paste(pic.rotate(180), REGION[:2])
        sheet.save(os.path.join(ns, f'textures/item/build_zone/{kind}.png'))
        model = json.loads(json.dumps(desk))
        model['textures'] = {k: f'cannons:item/build_zone/{kind}' for k in model['textures']}
        write_json(os.path.join(ns, f'models/entity/build_zone/desk_{kind}.json'), model)
        write_json(os.path.join(ns, f'items/build_zone_desk_{kind}.json'), {
            'model': {'type': 'minecraft:model', 'model': f'cannons:entity/build_zone/desk_{kind}'},
        })
        print('wrote', kind)


if __name__ == '__main__':
    main(sys.argv[1])
