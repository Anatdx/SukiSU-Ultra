# Third-party Libraries

This directory is managed by CMake FetchContent. Dependencies are automatically downloaded during the build process.

## Dependencies

### PicoSHA2

- **License**: MIT
- **Source**: https://github.com/okdshin/PicoSHA2
- **Version**: v1.0.1 (managed by FetchContent in CMakeLists.txt)
- **Purpose**: SHA-256 hash calculation for APK signature verification

### Management

Dependencies are declared in `CMakeLists.txt` using `FetchContent_Declare`. 

To update a dependency version, modify the `GIT_TAG` in CMakeLists.txt:

```cmake
FetchContent_Declare(
    picosha2
    GIT_REPOSITORY https://github.com/okdshin/PicoSHA2.git
    GIT_TAG v1.0.1  # Update this version
    GIT_SHALLOW TRUE
)
```

Dependabot will automatically detect and create PRs for version updates.

