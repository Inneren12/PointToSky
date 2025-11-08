# Point-to-Sky Data Safety (S9.C)

| Data category | Data types | Collection | Purpose | Storage | Sharing |
| --- | --- | --- | --- | --- | --- |
| Approximate location | City-level coordinates derived from device location APIs | Requested on-device when the user allows "location while in use". | Calculating visible sky and synchronising sky maps between phone and watch. | Stored only in app memory and local preferences. Not transmitted to servers. | Not shared. |
| Device sensors | Accelerometer, gyroscope, magnetometer, light sensor readings | Read directly from the device sensors during active sessions. | Orienting the sky map, Aim/Identify tools, and AR overlays. | Processed in memory only. No persistent copies. | Not shared. |
| Camera (mobile only) | Live camera preview frames | Accessed only when the AR mode is enabled and permission is granted. | Rendering the augmented reality overlay. | Processed on device without retention. | Not shared. |
| App preferences | Feature toggles, manual coordinates, onboarding confirmation | Saved locally after explicit user actions. | Restoring user configuration across sessions. | Stored in encrypted app storage (DataStore/SharedPreferences). | Not shared. |

## Optional services (future S9.D work)

If Firebase Crashlytics or similar crash reporting is enabled in a future release, diagnostic data (stack traces, device model, OS version, and anonymised app identifiers) may be transmitted to the respective service providers. This feature is currently **disabled** in S9.C and would require a dedicated privacy update and user notice before activation.
