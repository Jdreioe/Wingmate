# Use the Rust partner-window driver

The Linux Rust controller is the sole hardware driver for the Tobii i13 partner window. Linux owns its device-specific preferences, while shared code provides only the active Message to display. Removing the duplicate Kotlin driver prevents competing hardware ownership and keeps device concerns outside the shared settings model.
