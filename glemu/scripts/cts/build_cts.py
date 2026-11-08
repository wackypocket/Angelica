#!/usr/bin/env python3
"""Builds the Khronos OpenGL conformance suite as a library that glemu's test JVM loads.

usage: build_cts.py <work directory>   needs git, cmake, ninja, a C++ compiler and JAVA_HOME; takes around 5 GB

Run the result with cts_run.py.
"""
import os
import pathlib
import shutil
import subprocess
import sys

HERE = pathlib.Path(__file__).resolve().parent
REPOSITORY = 'https://github.com/KhronosGroup/VK-GL-CTS.git'
MODULE_EXECUTABLE = 'add_executable(${MODULE_NAME} ${PROJECT_SOURCE_DIR}/framework/platform/tcuMain.cpp ${ENTRY})'
SHARED_MODULE = '''if (DEQP_ANGELICA_SHARED)
			add_library(${MODULE_NAME} SHARED ${PROJECT_SOURCE_DIR}/framework/platform/tcuMain.cpp ${ENTRY})
			target_compile_definitions(${MODULE_NAME} PRIVATE main=angelica_cts_main)
		else ()
			''' + MODULE_EXECUTABLE + '''
		endif ()'''


def main():
    if len(sys.argv) != 2 or sys.argv[1].startswith('-'):
        print(__doc__)
        return 2
    work = pathlib.Path(sys.argv[1]).resolve()
    source, build = work / 'src', work / 'build'
    if not source.exists():
        subprocess.check_call(['git', 'clone', '--depth', '1', REPOSITORY, str(source)])
    # Fetched until it has once succeeded, so a run that failed partway through fetching picks it up again.
    fetched = source / '.angelica-sources-fetched'
    if not fetched.exists():
        subprocess.check_call([sys.executable, 'external/fetch_sources.py'], cwd=source)
        fetched.touch()
    target = source / 'targets/angelica'
    target.mkdir(exist_ok=True)
    shutil.copy(HERE / 'angelica.cmake', target)
    shutil.copy(HERE / 'tcuAngelicaPlatform.cpp', target)
    lists = source / 'CMakeLists.txt'
    text = lists.read_text()
    if 'DEQP_ANGELICA_SHARED' not in text:
        lists.write_text(text.replace(MODULE_EXECUTABLE, SHARED_MODULE, 1))
    configure = ['cmake', '-G', 'Ninja', '-S', str(source), '-B', str(build), '-DDEQP_TARGET=angelica', '-DCMAKE_BUILD_TYPE=Release']
    # The library has to match the JVM loading it, whatever architecture these tools happen to run as.
    if sys.platform == 'darwin':
        release = (pathlib.Path(os.environ['JAVA_HOME']) / 'release').read_text()
        configure.append('-DCMAKE_OSX_ARCHITECTURES=' + ('arm64' if 'aarch64' in release else 'x86_64'))
    subprocess.check_call(configure)
    subprocess.check_call(['ninja', '-C', str(build), 'glcts', 'glcts-data'])
    return 0


if __name__ == '__main__':
    sys.exit(main())
