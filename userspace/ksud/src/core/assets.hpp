#pragma once

#include <string>

namespace ksud {

// Binary paths
extern const char* RESETPROP_PATH;
extern const char* BUSYBOX_PATH;
extern const char* BOOTCTL_PATH;

// Ensure all binary assets are extracted
bool ensure_binaries(bool ignore_if_exist);

}  // namespace ksud
