"""Build the pinned source derivative and install it into both local test packs."""
import json
from build_gtceu_kubejs_bridge import build
from gtceu_artifact import ROOT, FOUNDATION, install, pins, cache_original


def main():
    cache_original()
    result = build()
    assert result['jar_sha256'] == pins()['sha256'], 'Rebuilt GTCEu differs from the validated artifact'
    for relative in (FOUNDATION, 'work/neoforge-smoke'):
        installed = install(ROOT / relative, ROOT / result['jar'])
        print(json.dumps({'pack': str(relative), 'installed': installed}), flush=True)


if __name__ == '__main__':
    main()
