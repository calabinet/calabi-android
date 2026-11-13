"""Pinned Android RDP SDK. Invoke through build-rdp-android.ps1 and its shared lease."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import tarfile
import tempfile
from rdp_mediacodec_patch import apply as patch_mediacodec

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / 'apps/client-android'
WORK = Path(tempfile.gettempdir()) / 'calabi-android-rdp'
DOWNLOADS = APP / '.gradle/calabi-rdp/downloads'
SOURCES = (
    ('freerdp-3.33.0.tar.xz', 'https://pub.freerdp.com/releases/freerdp-3.33.0.tar.xz',
     '9cea38e972e3775e1ce83b30e9ae0cbf8d00af1c708c640e6244fc81bdb1e08b'),
    ('openssl-3.6.5.tar.gz', 'https://github.com/openssl/openssl/releases/download/openssl-3.6.5/openssl-3.6.5.tar.gz',
     'a2157c2830efdec3788939b00c9b0638306d3f0bbb76dc4832ee503bb397df98'),
    ('Locale-Maketext-Simple-0.21.tar.gz', 'https://cpan.metacpan.org/authors/id/J/JE/JESSE/Locale-Maketext-Simple-0.21.tar.gz',
     'b009ff51f4fb108d19961a523e99b4373ccf958d37ca35bf1583215908dca9a9'),
    ('ExtUtils-MakeMaker-7.76.tar.gz', 'https://cpan.metacpan.org/authors/id/B/BI/BINGOS/ExtUtils-MakeMaker-7.76.tar.gz',
     '30bcfd75fec4d512e9081c792f7cb590009d9de2fe285ffa8eec1be35a5ae7ca'),
    ('perl-5.40.3.tar.xz', 'https://www.cpan.org/src/5.0/perl-5.40.3.tar.xz',
     '65f63b4763ab6cb9bb3d5731dd10369e1705be3c59be9847d453eb60b349ab43'),
)


def run(args, **kwargs):
    print('+', ' '.join(map(str, args)), flush=True)
    WORK.mkdir(parents=True, exist_ok=True)
    with (WORK / 'build.log').open('a', encoding='utf-8') as log:
        result = subprocess.run(list(map(str, args)), stdout=log, stderr=subprocess.STDOUT, **kwargs)
    if result.returncode:
        print('\n'.join((WORK / 'build.log').read_text(encoding='utf-8', errors='replace').splitlines()[-70:]), flush=True)
        raise subprocess.CalledProcessError(result.returncode, args)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--android-home', type=Path, required=True)
    parser.add_argument('--abi', choices=['arm64-v8a'], default='arm64-v8a')
    parser.add_argument('--test-clipboard', action='store_true')
    args = parser.parse_args()
    if not os.environ.get('CALABI_BUILD_LEASE'):
        raise SystemExit('Use build-rdp-android.ps1 for the shared build lease')
    tools = json.loads((APP / 'mobile-toolchain.json').read_text())
    ndk = args.android_home / 'ndk' / tools['ndk']
    llvm = ndk / 'toolchains/llvm/prebuilt/windows-x86_64/bin'
    for name, url, digest in SOURCES:
        archive = DOWNLOADS / name
        archive.parent.mkdir(parents=True, exist_ok=True)
        if not archive.exists():
            part = archive.with_suffix('.part')
            run(['curl.exe', '-fL', '--retry', '2', '--connect-timeout', '20', '--max-time', '300', url, '-o', part])
            part.replace(archive)
        if hashlib.sha256(archive.read_bytes()).hexdigest() != digest:
            raise SystemExit(f'Source hash mismatch: {name}')
        directory = WORK / name.removesuffix('.tar.xz').removesuffix('.tar.gz')
        entrypoint = ('CMakeLists.txt' if name.startswith('freerdp-') else
                      'Configure' if name.startswith(('openssl-', 'perl-')) else 'Makefile.PL')
        # Windows can reclaim part of TEMP between builds. A leftover patched
        # subdirectory does not mean the verified source tree is still present.
        if not (directory / entrypoint).is_file():
            WORK.mkdir(parents=True, exist_ok=True)
            with tarfile.open(archive) as source:
                source.extractall(WORK, filter='data')
    sdk = WORK / args.abi / 'sdk'
    ssl = WORK / 'openssl-3.6.5'
    # Each ABI has independent generated makefiles; source downloads stay shared.
    ssl_build = WORK / args.abi / 'openssl'
    ssl_build.mkdir(parents=True, exist_ok=True)
    target = 'android-arm64'
    bash = Path(shutil.which('git')).parents[1] / 'bin/bash.exe'
    env = dict(os.environ)
    env['MSYS2_ENV_CONV_EXCL'] = 'PERL5LIB'
    ndk_unix = ndk.resolve().as_posix()
    env['ANDROID_NDK_ROOT'] = '/' + ndk_unix[0].lower() + ndk_unix[2:]
    perl_lib = (WORK / 'Locale-Maketext-Simple-0.21/lib').as_posix()
    env['PERL5LIB'] = '/' + perl_lib[0].lower() + perl_lib[2:]
    maker_lib = (WORK / 'ExtUtils-MakeMaker-7.76/lib').as_posix()
    env['PERL5LIB'] += ':/' + maker_lib[0].lower() + maker_lib[2:]
    for module in (WORK / 'perl-5.40.3/cpan').iterdir():
        if module.name.lower().startswith(('pod-', 'podlators')):
            path = (module / 'lib').as_posix()
            env['PERL5LIB'] += ':/' + path[0].lower() + path[2:]
    env['PATH'] = str(llvm) + os.pathsep + str(Path(shutil.which('cmake')).parent) + os.pathsep + env['PATH']
    if not (sdk / 'lib/libcrypto.a').exists():
        configure = shlex.quote((ssl / 'Configure').as_posix())
        prefix = shlex.quote(sdk.as_posix())
        # Git Bash must use its MSYS Perl and NDK clang, even when a Windows
        # runner has Strawberry Perl or another LLVM earlier in its host PATH.
        ndk_bin = shlex.quote(env['ANDROID_NDK_ROOT'] + '/toolchains/llvm/prebuilt/windows-x86_64/bin')
        script = (f'export ANDROID_NDK_ROOT={shlex.quote(env["ANDROID_NDK_ROOT"])}; '
                  f'export PATH={ndk_bin}:/usr/bin:"$PATH"; '
                  f'/usr/bin/perl {configure} {target} -D__ANDROID_API__=26 no-shared no-module no-tests no-apps no-docs --prefix={prefix} --libdir=lib && make -j8 && make install_sw')
        run([bash, '--noprofile', '--norc', '-c', script], cwd=ssl_build, env=env)
    source = WORK / 'freerdp-3.33.0'
    patch_mediacodec(source, DOWNLOADS / 'freerdp-3.33.0.tar.xz')
    obj = WORK / args.abi / 'freerdp'
    disabled = ('WITH_CLIENT', 'WITH_SERVER', 'WITH_SERVER_INTERFACE', 'WITH_SERVER_CHANNELS',
                'WITH_SAMPLE', 'BUILD_TESTING', 'WITH_MANPAGES', 'WITH_WINPR_TOOLS', 'WITH_CCACHE',
                'WITH_FFMPEG', 'WITH_SWSCALE', 'WITH_OPENH264', 'WITH_JPEG', 'WITH_CAIRO',
                'WITH_WEBVIEW', 'WITH_URIPARSER', 'WITH_FUSE', 'WITH_OPUS', 'WITH_MBEDTLS',
                'WITH_PULSE', 'WITH_ALSA', 'WITH_X11', 'WITH_WAYLAND', 'WITH_CUPS', 'WITH_PCSC',
                'WITH_SDL', 'WITH_SDL2', 'WITH_CJSON', 'WITH_PKCS11')
    common = ['-G', 'Ninja', f'-DCMAKE_TOOLCHAIN_FILE={(ndk / "build/cmake/android.toolchain.cmake").as_posix()}',
              f'-DANDROID_ABI={args.abi}', '-DANDROID_PLATFORM=android-26', '-DCMAKE_BUILD_TYPE=Release',
              '-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON']
    run(['cmake', '-S', source, '-B', obj, *common,
         f'-DCMAKE_INSTALL_PREFIX={sdk.as_posix()}', '-DCMAKE_INSTALL_LIBDIR=lib',
         f'-DOPENSSL_ROOT_DIR={sdk.as_posix()}', f'-DOPENSSL_INCLUDE_DIR={(sdk / "include").as_posix()}',
         f'-DOPENSSL_CRYPTO_LIBRARY={(sdk / "lib/libcrypto.a").as_posix()}',
         f'-DOPENSSL_SSL_LIBRARY={(sdk / "lib/libssl.a").as_posix()}',
         '-DBUILD_SHARED_LIBS=ON', '-DWITH_OPENSSL=ON', '-DWITH_JSON_DISABLED=ON', '-DWITH_MEDIACODEC=ON',
         '-DWITH_CLIENT_COMMON=ON', '-DWITH_CHANNELS=ON', '-DWITH_CLIENT_CHANNELS=ON',
         '-DCHANNEL_CLIPRDR_CLIENT=ON', '-DCHANNEL_CLIPRDR_SERVER=OFF',
         *[f'-DCHANNEL_{p.parent.name.upper()}={"ON" if p.parent.name in ("cliprdr", "drdynvc", "rdpgfx") else "OFF"}'
           for p in sorted((source / 'channels').glob('*/ChannelOptions.cmake'))],
         '-DWITHOUT_FREERDP_3x_DEPRECATED=ON', *[f'-D{option}=OFF' for option in disabled]])
    run(['cmake', '--build', obj, '--target', 'freerdp-client', '--parallel', '8'])
    run(['cmake', '--install', obj])
    native = WORK / args.abi / 'bridge'
    # The shared bridge directory is reused by development and pinned release trees.
    # Regenerate CMake's source-path cache while retaining the compiled SDK.
    run(['cmake', '--fresh', '-S', APP / 'app/src/main/cpp', '-B', native, *common, f'-DCALABI_RDP_SDK={sdk.as_posix()}',
         f'-DCALABI_RDP_TESTS={"ON" if args.test_clipboard else "OFF"}'])
    run(['cmake', '--build', native, '--parallel', '8'])
    output = APP / 'app/libs/rdp' / args.abi
    output.mkdir(parents=True, exist_ok=True)
    for lib in ('libfreerdp3.so', 'libwinpr3.so', 'libfreerdp-client3.so'):
        shutil.copy2(sdk / 'lib' / lib, output / lib)
    shutil.copy2(native / 'libcalabi-rdp.so', output / 'libcalabi-rdp.so')
    for lib in output.glob('*.so'):
        run([llvm / 'llvm-strip.exe', '--strip-unneeded', lib])
    licenses = APP / 'app/src/main/assets/licenses'
    shutil.copy2(source / 'LICENSE', licenses / 'FreeRDP.txt')
    shutil.copy2(ssl / 'LICENSE.txt', licenses / 'OpenSSL.txt')
    (output.parent / 'build.json').write_text(json.dumps({
        'abi': args.abi, 'ndk': tools['ndk'], 'sources': {name: digest for name, _, digest in SOURCES},
        'channels': ['cliprdr (manual Unicode text only)', 'drdynvc', 'rdpgfx (AVC420 / named hardware MediaCodec)'],
        'mediacodec_patch_sha256': hashlib.sha256((ROOT / 'scripts/mobile/rdp_mediacodec_patch.py').read_bytes()).hexdigest(),
        'mediacodec_surface_sha256': hashlib.sha256((ROOT / 'scripts/mobile/rdp_mediacodec_surface.c.inc').read_bytes()).hexdigest(),
        'libraries': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in output.glob('*.so')},
    }, indent=2) + '\n')
    print('Android RDP native libraries ready:', output, flush=True)


if __name__ == '__main__':
    main()
