@echo off
setlocal
rem vcvars64 chokes on this machine's PATH (a "(...)"/Bitvise entry breaks its
rem internal IF parsing), so start from a minimal, known-good PATH.
set "PATH=C:\Windows\system32;C:\Windows;C:\Windows\System32\Wbem"
call "C:\Program Files\Microsoft Visual Studio\18\Community\VC\Auxiliary\Build\vcvars64.bat" >nul 2>&1
if errorlevel 1 echo VCVARS_FAILED
set "VSCM=C:\Program Files\Microsoft Visual Studio\18\Community\Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe"
set "VSNINJA=C:\Program Files\Microsoft Visual Studio\18\Community\Common7\IDE\CommonExtensions\Microsoft\CMake\Ninja\ninja.exe"
"%VSCM%" -S "%~1\parity_src" -B "%~1\parity_build" -G Ninja -DCMAKE_MAKE_PROGRAM="%VSNINJA%" -DCMAKE_BUILD_TYPE=Release -DCMAKE_C_COMPILER=clang-cl -DCMAKE_CXX_COMPILER=clang-cl
if errorlevel 1 exit /b 1
"%VSCM%" --build "%~1\parity_build" --target razparity
