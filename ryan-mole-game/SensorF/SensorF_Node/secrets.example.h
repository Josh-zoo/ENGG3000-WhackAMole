// Copy this file to secrets.h (same folder) and fill in your own values.
// secrets.h is ignored by git, so your password never goes to GitHub.
#pragma once

const char *WIFI_SSID = "YOUR_HOTSPOT_NAME";      // must match box 1 exactly
const char *WIFI_PASSWORD = "YOUR_HOTSPOT_PASSWORD";

// Box 1's MAC address, printed by box 1 at startup as "HUB MAC ADDRESS"
uint8_t hubAddress[] = {0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
