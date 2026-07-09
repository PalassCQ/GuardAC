/*
 * This file is part of GuardAC - https://github.com/PalassCQ/GuardAC
 * Copyright (C) 2026 GuardAC
 *
 * GuardAC is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * GuardAC is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package dev.guardac.util

enum class Message(val key: String) {
    PREFIX("prefix"),
    NO_PERMISSION("no-permission"),
    PLAYER_NOT_FOUND("player-not-found"),
    RUN_AS_PLAYER("run-as-player"),
    RELOAD_SUCCESS("reload-success"),

    ALERTS_ENABLED("alerts-enabled"),
    ALERTS_DISABLED("alerts-disabled"),
    ALERTS_FORMAT("alerts-format"),
    ALERTS_CONSOLE_FORMAT("alerts-console-format"),
    FINGERPRINT_ALERT("fingerprint-alert"),
    SUSPICIOUS_ALERT("suspicious-alert"),
    UPDATE_DOWNLOADED("update-downloaded"),

    SUPPRESSION_ISOLATE_NOTICE("suppression-isolate-notice"),
    PROFILE_SUPPRESSION("profile-suppression"),
    DEBUG_SUPPRESSION("debug-suppression"),

    PROFILE_HEADER("profile-header"),
    PROFILE_AI_BUFFER("profile-ai-buffer"),
    PROFILE_AI_VL("profile-ai-vl"),
    PROFILE_NO_DATA("profile-no-data"),
    PROFILE_PING("profile-ping"),
    PROFILE_SESSION("profile-session"),
    PROFILE_SENSITIVITY("profile-sensitivity"),
    PROFILE_RIDING("profile-riding"),
    PROFILE_BRAND("profile-brand"),
    PROFILE_AVG_PROB("profile-avg-prob"),

    SUSPICIOUS_HEADER("suspicious-header"),
    SUSPICIOUS_ENTRY("suspicious-entry"),
    SUSPICIOUS_EMPTY("suspicious-empty"),

    DEBUG_HEADER("debug-header"),
    DEBUG_ROTATION("debug-rotation"),
    DEBUG_DELTA("debug-delta"),
    DEBUG_ACCEL("debug-accel"),
    DEBUG_JERK("debug-jerk"),
    DEBUG_GCD("debug-gcd"),
    DEBUG_MODE_XY("debug-mode-xy"),
    DEBUG_AI_BUFFER("debug-ai-buffer"),
    DEBUG_RIDING("debug-riding"),
    DEBUG_MODE_STATUS("debug-mode-status"),
    DEBUG_MODE_ON("debug-mode-on"),
    DEBUG_MODE_OFF("debug-mode-off"),
    DEBUG_IDLE("debug-idle"),

    PROB_STARTED("prob-started"),
    PROB_STOPPED("prob-stopped"),
    PROB_ACTIONBAR("prob-actionbar"),
    PROB_NO_DATA("prob-no-data"),

    MONITOR_ENABLED("monitor-enabled"),
    MONITOR_DISABLED("monitor-disabled"),
    MONITOR_HIT("monitor-hit"),

    EXEMPT_ADDED("exempt-added"),
    EXEMPT_REMOVED("exempt-removed"),
    EXEMPT_STATUS_EXEMPT("exempt-status-exempt"),
    EXEMPT_STATUS_NOT_EXEMPT("exempt-status-not-exempt"),
    EXEMPT_ALREADY("exempt-already"),
    EXEMPT_NOT_FOUND("exempt-not-found"),

    RESET_SUCCESS("reset-success"),
    RESET_ALL_SUCCESS("reset-all-success"),

    PUNISH_SUCCESS("punish-success"),
    PUNISH_NO_DATA("punish-no-data"),
    UNSAFE_NAME_KICK("unsafe-name-kick"),

    SCAN_STARTED("scan-started"),
    SCAN_ALREADY("scan-already"),
    SCAN_NO_DATA("scan-no-data"),
    SCAN_REPORT_HEADER("scan-report-header"),
    SCAN_REPORT_STATS("scan-report-stats"),
    SCAN_REPORT_MODELS("scan-report-models"),
    SCAN_VERDICT_CLEAN("scan-verdict-clean"),
    SCAN_VERDICT_SUSPICIOUS("scan-verdict-suspicious"),
    SCAN_VERDICT_CHEATING("scan-verdict-cheating"),
    SCAN_PARTIAL_NOTE("scan-partial-note"),
    USAGE_SCAN("usage-scan"),
    HELP_SCAN("help-scan"),

    STATS_HEADER("stats-header"),
    STATS_ONLINE("stats-online"),
    STATS_SUSPICIOUS("stats-suspicious"),
    STATS_TOTAL_FLAGS("stats-total-flags"),
    STATS_AI_STATUS("stats-ai-status"),
    STATS_DC_SESSIONS("stats-dc-sessions"),
    STATS_UPTIME("stats-uptime"),

    LOG_HEADER("log-header"),
    LOG_EMPTY("log-empty"),

    HISTORY_HEADER("history-header"),
    HISTORY_ENTRY("history-entry"),
    HISTORY_EMPTY("history-empty"),
    USAGE_HISTORY("usage-history"),
    HELP_HISTORY("help-history"),

    DATACOLLECT_START_SUCCESS("datacollect-start-success"),
    DATACOLLECT_START_RESTARTED("datacollect-start-restarted"),
    DATACOLLECT_STOP_SUCCESS("datacollect-stop-success"),
    DATACOLLECT_STOP_FAIL("datacollect-stop-fail"),
    DATACOLLECT_CANCEL_SUCCESS("datacollect-cancel-success"),
    DATACOLLECT_STATUS_HEADER("datacollect-status-header"),
    DATACOLLECT_STATUS_ENTRY("datacollect-status-entry"),
    DATACOLLECT_STATUS_EMPTY("datacollect-status-empty"),
    DATACOLLECT_STATUS_NO_SESSION("datacollect-status-no-session"),
    DATACOLLECT_INVALID_TYPE("datacollect-invalid-type"),
    DATACOLLECT_DETAILS_REQUIRED("datacollect-details-required"),

    CROSSSERVER_ALERT("crossserver-alert"),
    CROSSSERVER_ENABLED("crossserver-enabled"),
    CROSSSERVER_DISABLED("crossserver-disabled"),

    SUSPECTS_MENU_OPEN("suspects-menu-open"),
    SUSPECTS_MENU_TITLE("suspects-menu-title"),
    SUSPECTS_MENU_EMPTY("suspects-menu-empty"),
    SUSPECTS_MENU_PREV("suspects-menu-prev"),
    SUSPECTS_MENU_NEXT("suspects-menu-next"),
    SUSPECTS_MENU_REFRESH("suspects-menu-refresh"),
    SUSPECTS_MENU_CLOSE("suspects-menu-close"),
    MENU_EMPTY_TITLE("menu-empty-title"),
    MENU_SKULL_CONF("menu-skull-conf"),
    MENU_SKULL_AVG("menu-skull-avg"),
    MENU_SKULL_VL("menu-skull-vl"),
    MENU_SKULL_PING("menu-skull-ping"),
    MENU_SKULL_CLICK("menu-skull-click"),
    MENU_PAGE("menu-page"),
    MENU_REFRESH_LORE("menu-refresh-lore"),
    MENU_PLAYER_OFFLINE("menu-player-offline"),
    MENU_UNSAFE_NAME("menu-unsafe-name"),
    REPUTATION_NOTICE("reputation-notice"),
    ALERT_CLICK_HINT("alert-click-hint"),
    UNIT_HOURS("unit-h"),
    UNIT_MINUTES("unit-m"),
    UNIT_SECONDS("unit-s"),
    COMMON_YES("common-yes"),
    COMMON_NO("common-no"),
    COMMON_ON("common-on"),
    COMMON_OFF("common-off"),
    STATS_DETECTIONS("stats-detections"),

    USAGE_PROFILE("usage-profile"),
    USAGE_PROB("usage-prob"),
    USAGE_EXEMPT("usage-exempt"),
    USAGE_EXEMPT_REMOVE("usage-exempt-remove"),
    USAGE_EXEMPT_STATUS("usage-exempt-status"),
    USAGE_RESET("usage-reset"),
    USAGE_PUNISH("usage-punish"),
    USAGE_STATS("usage-stats"),
    USAGE_DC_STOP("usage-dc-stop"),
    USAGE_DC_CANCEL("usage-dc-cancel"),

    HELP_HEADER("help-header"),
    HELP_RELOAD("help-reload"),
    HELP_ALERTS("help-alerts"),
    HELP_MONITOR("help-monitor"),
    HELP_PROFILE("help-profile"),
    HELP_SUSPICIOUS("help-suspicious"),
    HELP_MENU("help-menu"),
    HELP_DEBUG("help-debug"),
    HELP_PROB("help-prob"),
    HELP_EXEMPT("help-exempt"),
    HELP_RESET("help-reset"),
    HELP_PUNISH("help-punish"),
    HELP_STATS("help-stats"),
    HELP_LOG("help-log"),
    HELP_CROSSSERVER("help-crossserver"),
    HELP_DATACOLLECT("help-datacollect"),
    HELP_FOOTER("help-footer"),

    DC_HELP_HEADER("dc-help-header"),
    DC_HELP_START("dc-help-start"),
    DC_HELP_STOP("dc-help-stop"),
    DC_HELP_CANCEL("dc-help-cancel"),
    DC_HELP_STATUS("dc-help-status"),
    DC_HELP_FOOTER("dc-help-footer"),
}
