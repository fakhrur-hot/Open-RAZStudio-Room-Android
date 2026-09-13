#pragma once
#include <atomic>

namespace raw_v3 {

// Incremented each time the workspace selector dialog is opened.
// Both the GL renderer and apply_macro compare their cached generation
// against this value; a mismatch triggers a full verbose param dump.
extern std::atomic<int> g_debugLogGeneration;

// Call once when workspace selector opens. Thread-safe.
inline void resetAdjustmentDebugLog() {
    g_debugLogGeneration.fetch_add(1, std::memory_order_relaxed);
}

} // namespace raw_v3
