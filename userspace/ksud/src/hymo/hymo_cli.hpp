// hymo_cli.hpp - HymoFS module management CLI wrapper
#pragma once

#include <string>
#include <vector>

namespace hymo {

// Main hymo command handler for ksud integration
// Returns: 0 on success, non-zero on failure
int cmd_hymo(const std::vector<std::string>& args);

// Print hymo help
void print_hymo_help();

}  // namespace hymo
