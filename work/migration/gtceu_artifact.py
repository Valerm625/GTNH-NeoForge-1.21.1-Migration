"""Keep original download identity separate from the source-built runtime JAR.

Checks are read-only except explicit cache_original/install calls. Unknown files
are preserved and rejected; a known interrupted install can be resumed. No game
launch, EULA change, source lock rewrite, or missing-feature fallback occurs.
"""
from pathlib import Path
import hashlib
import json
import os
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[2]
FOUNDATION = Path('outputs/GTNH-NeoForge-1.21.1-Migration-Foundation')
RECEIPT = Path('docs/gtceu-derived-artifact.json')


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def pins(root=ROOT):
    pin = read(root / 'locks/gtceu-derived-artifact.json')
    build = read(root / 'locks/gtceu-kubejs72-build.json')
    require(pin['format'] == 1 and not pin['full_parity'], 'Unknown derived artifact schema')
    require(pin['original_filename'] == build['original_jar'] and
            pin['original_sha256'] == build['original_sha256'] and
            pin['source_commit'] == build['source_commit'], 'Derived artifact base identity differs')
    require(sha(root / 'work/compat-mod/patches/gtceu-kubejs72.json') == pin['patch_sha256'],
            'Changed GTCEu source patch; rebuild and revalidate before updating artifact pin')
    return pin


def original_path(root=ROOT):
    pin = pins(root)
    return root / 'work/cache/original-artifacts' / pin['original_sha256'] / pin['original_filename']


def verify_original(path, root=ROOT):
    pin = pins(root)
    require(path.is_file() and sha(path) == pin['original_sha256'], 'Original GTCEu SHA256 differs: ' + str(path))
    rows = read(root / 'locks/artifacts.lock.json')['artifacts']
    row = next(v for v in rows if v['filename'] == pin['original_filename'])
    for algorithm, expected in row['hashes'].items():
        with path.open('rb') as stream:
            require(hashlib.file_digest(stream, algorithm).hexdigest() == expected,
                    'Original GTCEu download hash differs: ' + algorithm)
    return path


def original_jar(root=ROOT, pack=None):
    """Find and verify the base input without modifying either directory."""
    cached = original_path(root)
    if cached.exists():
        return verify_original(cached, root)
    pack = root / FOUNDATION if pack is None else pack
    return verify_original(pack / 'mods' / pins(root)['original_filename'], root)


def cache_original(root=ROOT, pack=None):
    source = original_jar(root, pack)
    destination = original_path(root)
    if source != destination:
        destination.parent.mkdir(parents=True, exist_ok=True)
        # Exclusive creation: a conflicting cache is never overwritten.
        with destination.open('xb') as stream:
            stream.write(source.read_bytes())
        verify_original(destination, root)
    return destination


def receipt(root=ROOT):
    pin = pins(root)
    return {'format': 1, 'filename': pin['filename'], 'sha256': pin['sha256'],
            'original_filename': pin['original_filename'], 'original_sha256': pin['original_sha256'],
            'source_commit': pin['source_commit'], 'patch_sha256': pin['patch_sha256'],
            'artifact_lock_sha256': sha(root / 'locks/gtceu-derived-artifact.json'),
            'built_locally': True, 'full_parity': False}


def verify_derived(path, root=ROOT, base=None):
    pin = pins(root)
    require(path.is_file() and sha(path) == pin['sha256'], 'Derived GTCEu SHA256 differs: ' + str(path))
    base = original_jar(root) if base is None else verify_original(base, root)
    with zipfile.ZipFile(base) as old, zipfile.ZipFile(path) as new:
        metadata = 'META-INF/gtnh-migration-kubejs72.json'
        require(len(new.namelist()) == len(set(new.namelist())), 'Duplicate derived JAR entries')
        require(set(new.namelist()) == set(old.namelist()) | {metadata}, 'Derived JAR entries differ')
        expected = {'original_jar_sha256': pin['original_sha256'], 'source_commit': pin['source_commit'],
                    'patch_sha256': pin['patch_sha256'], 'old_output_control': False,
                    'source_sha256': pin['source_sha256']}
        require(json.loads(new.read(metadata)) == expected, 'Derived JAR provenance differs')
        unchanged = 0
        for name in old.namelist():
            value = new.read(name)
            if name in pin['compiled_class_sha256']:
                require(hashlib.sha256(value).hexdigest() == pin['compiled_class_sha256'][name], name)
            else:
                require(value == old.read(name), 'Unmodified GTCEu entry changed: ' + name)
                unchanged += 1
        require(unchanged == pin['unchanged_original_entries'], 'Preserved entry count differs')
    return path


def runtime_jar(pack=None, root=ROOT, require_derived=True):
    pack = root / FOUNDATION if pack is None else pack
    pin = pins(root)
    original = pack / 'mods' / pin['original_filename']
    derived = pack / 'mods' / pin['filename']
    require(not (original.exists() and derived.exists()), 'Interrupted GTCEu install; rerun installer before loading')
    if derived.exists():
        verify_derived(derived, root)
        require((pack / RECEIPT).is_file() and read(pack / RECEIPT) == receipt(root), 'Missing or changed GTCEu installation receipt')
        return derived
    require(not require_derived, 'Derived GTCEu must be built and installed before loading')
    return verify_original(original, root)


def install(pack, artifact, root=ROOT):
    """Idempotent, resumable install; only the two exact known identities can change."""
    pin = pins(root)
    verify_derived(artifact, root)
    cache_original(root, pack)
    mods = pack / 'mods'
    require(mods.is_dir() and mods.resolve().is_relative_to(pack.resolve()), 'Invalid mod directory')
    old = mods / pin['original_filename']
    new = mods / pin['filename']
    for path in (old, new):
        require(path.resolve().is_relative_to(mods.resolve()), 'Artifact path escapes mod directory')
    if old.exists():
        verify_original(old, root)
    installed_hash = sha(new) if new.exists() else None
    if installed_hash and installed_hash != pin['sha256']:
        previous = next((row for row in pin.get('previous_artifacts', [])
                         if row['filename'] == new.name and row['sha256'] == installed_hash), None)
        require(previous is not None, 'Derived GTCEu SHA256 differs: ' + str(new))
        with zipfile.ZipFile(new) as archive:
            require(hashlib.sha256(archive.read('META-INF/gtnh-migration-kubejs72.json')).hexdigest()
                    == previous['provenance_sha256'], 'Previous GTCEu provenance differs')
        retained = root / 'work/cache/derived-artifacts' / installed_hash / new.name
        if retained.exists():
            require(sha(retained) == installed_hash, 'Changed retained previous GTCEu')
        else:
            retained.parent.mkdir(parents=True, exist_ok=True)
            with retained.open('xb') as stream:
                stream.write(new.read_bytes())
        require(sha(retained) == installed_hash, 'Previous GTCEu retention failed')
    if installed_hash == pin['sha256']:
        verify_derived(new, root)
    else:
        temporary = mods / ('.gtceu-install-' + uuid.uuid4().hex + '.tmp')
        with temporary.open('xb') as stream:
            stream.write(artifact.read_bytes())
            stream.flush()
            os.fsync(stream.fileno())
        verify_derived(temporary, root)
        if installed_hash:
            require(sha(new) == installed_hash, 'GTCEu changed during upgrade; preserved')
            temporary.replace(new)
        else:
            temporary.rename(new)
    # If interrupted before this point both known JARs remain and runtime_jar
    # refuses loading. Rerunning this function resumes only after strict hashes.
    if old.exists():
        verify_original(old, root)
        verify_original(original_path(root), root)
        old.unlink()
    target = pack / RECEIPT
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_name(target.name + '.' + uuid.uuid4().hex + '.tmp')
    temporary.write_text(json.dumps(receipt(root), indent=2) + '\n', encoding='utf-8', newline='\n')
    temporary.replace(target)
    runtime_jar(pack, root)
    return receipt(root)
