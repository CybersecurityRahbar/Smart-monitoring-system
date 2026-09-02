package com.smarttraffic.app.core

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class AppLanguage { ENGLISH, ARABIC }

enum class VideoDisplayMode { FULLSCREEN, STANDARD, COMPACT }

object AppSettings {
    private const val PREFS = "smart_traffic_preferences"
    private const val LANGUAGE = "language"
    private const val DARK_MODE = "dark_mode"

    var language by mutableStateOf(AppLanguage.ENGLISH)
        private set

    var darkMode by mutableStateOf(true)
        private set

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        language = when (prefs.getString(LANGUAGE, "en")) {
            "ar" -> AppLanguage.ARABIC
            else -> AppLanguage.ENGLISH
        }
        darkMode = prefs.getBoolean(DARK_MODE, true)
    }

    fun setLanguage(context: Context, value: AppLanguage) {
        language = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(LANGUAGE, if (value == AppLanguage.ARABIC) "ar" else "en")
            .apply()
    }

    fun setDarkMode(context: Context, enabled: Boolean) {
        darkMode = enabled
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(DARK_MODE, enabled)
            .apply()
    }
}

fun tr(key: String): String = when (AppSettings.language) {
    AppLanguage.ENGLISH -> EnglishStrings[key] ?: key
    AppLanguage.ARABIC -> ArabicStrings[key] ?: EnglishStrings[key] ?: key
}

private val EnglishStrings = mapOf(
    "dashboard" to "Dashboard",
    "radar" to "Intelligent Radar",
    "live" to "Live Camera",
    "alerts" to "Alerts",
    "more" to "More",
    "back" to "Back",
    "monitoringCenter" to "Monitoring Center",
    "systemOnline" to "SYSTEM ONLINE",
    "situationalAwareness" to "Live situational awareness and system health",
    "primaryCamera" to "Primary camera",
    "localNetwork" to "ESP32-CAM • Local network",
    "cameraReady" to "Camera is ready for a local connection",
    "openLive" to "Open live",
    "deviceStatus" to "Device status",
    "vehicles" to "Vehicles",
    "averageSpeed" to "Average speed",
    "activeAlerts" to "Active alerts",
    "today" to "today",
    "active" to "active",
    "visionRadar" to "Vision radar",
    "standby" to "Standby",
    "running" to "Running",
    "noActiveEvents" to "No active events",
    "operationalStatus" to "Operational status",
    "capture" to "Capture",
    "cameraControl" to "Camera Control",
    "full" to "Full",
    "fullscreen" to "Fullscreen",
    "standard" to "Standard",
    "compact" to "Compact",
    "pip" to "PiP",
    "localFeed" to "ESP32-CAM • local feed",
    "streamNotConnected" to "Video pipeline ready • stream endpoint not connected",
    "intelligentRadar" to "INTELLIGENT RADAR",
    "radarDescription" to "Detection • tracking • trajectory • speed",
    "targetPolicy" to "Target policy",
    "radarFiltersDescription" to "Filter what the operator sees",
    "minimumSpeed" to "Minimum speed",
    "highConfidence" to "High confidence",
    "forward" to "Forward",
    "speedRange" to "Speed range",
    "stopRadar" to "Stop radar",
    "startRadar" to "Start radar",
    "analyzeMedia" to "Analyze media",
    "trackingStatus" to "Tracking status",
    "tracksReady" to "Tracks will appear when analysis is active",
    "tracks" to "tracks",
    "speed" to "speed",
    "incidentReports" to "Incident Reports",
    "newReport" to "New incident report",
    "recentReports" to "Recent reports",
    "incidentType" to "Incident type",
    "location" to "Location",
    "plateNumber" to "Vehicle / plate number",
    "priority" to "Priority",
    "details" to "Details",
    "submitReport" to "Submit report",
    "reportHint" to "Record an event for operator review and evidence collection.",
    "settings" to "Settings",
    "applicationSettings" to "Application",
    "systemSettings" to "System",
    "language" to "Language",
    "english" to "English",
    "arabic" to "العربية",
    "nightMode" to "Night mode",
    "nightModeDescription" to "Use the dark command-center appearance",
    "controlCenter" to "Operations",
    "analysisLab" to "Analysis Lab",
    "devices" to "Devices",
    "rules" to "Traffic Rules",
    "watchlist" to "Watchlist",
    "evidence" to "Evidence",
    "select" to "Select",
    "system" to "System",
    "online" to "Online",
    "standbyDescription" to "Ready for a local camera connection",
    "rulesDescription" to "Set the thresholds and actions used by traffic monitoring.",
    "speedPolicy" to "Speed policy",
    "speedLimit" to "Legal speed limit",
    "warningThreshold" to "Warning threshold",
    "captureThreshold" to "Evidence capture threshold",
    "minimumConfidence" to "Minimum vision confidence",
    "kmh" to "km/h",
    "rulesValuesDescription" to "Values are operator-defined and saved locally. The analysis engine will consume these settings when connected.",
    "enforcementActions" to "Violation actions",
    "captureOnViolation" to "Capture violation evidence",
    "createAlertOnViolation" to "Create operator alert",
    "preserveEvidence" to "Preserve linked evidence",
    "saveRules" to "Save rules",
    "reset" to "Reset",
    "rulesSaved" to "Traffic rule settings saved"
)

private val ArabicStrings = mapOf(
    "dashboard" to "لوحة القيادة",
    "radar" to "الرادار الذكي",
    "live" to "البث المباشر",
    "alerts" to "التنبيهات",
    "more" to "المزيد",
    "back" to "رجوع",
    "monitoringCenter" to "مركز المراقبة",
    "systemOnline" to "النظام متصل",
    "situationalAwareness" to "مراقبة لحظية للحالة وصحة النظام",
    "primaryCamera" to "الكاميرا الرئيسية",
    "localNetwork" to "ESP32-CAM • الشبكة المحلية",
    "cameraReady" to "الكاميرا جاهزة للاتصال المحلي",
    "openLive" to "فتح البث",
    "deviceStatus" to "حالة الجهاز",
    "vehicles" to "المركبات",
    "averageSpeed" to "متوسط السرعة",
    "activeAlerts" to "التنبيهات النشطة",
    "today" to "اليوم",
    "active" to "نشطة",
    "visionRadar" to "الرادار البصري",
    "standby" to "جاهز",
    "running" to "يعمل",
    "noActiveEvents" to "لا توجد أحداث نشطة",
    "operationalStatus" to "الحالة التشغيلية",
    "capture" to "التقاط",
    "cameraControl" to "التحكم بالكاميرا",
    "full" to "كامل",
    "fullscreen" to "ملء الشاشة",
    "standard" to "قياسي",
    "compact" to "مصغّر",
    "pip" to "نافذة عائمة",
    "localFeed" to "ESP32-CAM • بث محلي",
    "streamNotConnected" to "مسار الفيديو جاهز • لم يتم ربط نقطة البث بعد",
    "intelligentRadar" to "الرادار الذكي",
    "radarDescription" to "كشف • تتبع • مسار الحركة • سرعة",
    "targetPolicy" to "سياسة الأهداف",
    "radarFiltersDescription" to "تحكم بما يراه المشغّل",
    "minimumSpeed" to "الحد الأدنى للسرعة",
    "highConfidence" to "ثقة مرتفعة",
    "forward" to "اتجاه أمامي",
    "speedRange" to "نطاق السرعة",
    "stopRadar" to "إيقاف الرادار",
    "startRadar" to "تشغيل الرادار",
    "analyzeMedia" to "تحليل الوسائط",
    "trackingStatus" to "حالة التتبع",
    "tracksReady" to "ستظهر المسارات عند تشغيل التحليل",
    "tracks" to "مسارات",
    "speed" to "السرعة",
    "incidentReports" to "البلاغات والحوادث",
    "newReport" to "بلاغ حادث جديد",
    "recentReports" to "أحدث البلاغات",
    "incidentType" to "نوع الحادث",
    "location" to "الموقع",
    "plateNumber" to "رقم المركبة / اللوحة",
    "priority" to "الأولوية",
    "details" to "التفاصيل",
    "submitReport" to "إرسال البلاغ",
    "reportHint" to "سجّل الحدث لمراجعة المشغّل وحفظ الأدلة المرتبطة به.",
    "settings" to "الإعدادات",
    "applicationSettings" to "التطبيق",
    "systemSettings" to "النظام",
    "language" to "اللغة",
    "english" to "English",
    "arabic" to "العربية",
    "nightMode" to "الوضع الليلي",
    "nightModeDescription" to "استخدام مظهر مركز التحكم الداكن",
    "controlCenter" to "العمليات",
    "analysisLab" to "مختبر التحليل",
    "devices" to "الأجهزة",
    "rules" to "قواعد المرور",
    "watchlist" to "قائمة المراقبة",
    "evidence" to "الأدلة",
    "select" to "اختيار",
    "system" to "النظام",
    "online" to "متصل",
    "standbyDescription" to "جاهز للاتصال بكاميرا محلية",
    "rulesDescription" to "حدد الحدود والقواعد والإجراءات التي يستخدمها نظام المراقبة المرورية.",
    "speedPolicy" to "سياسة السرعة",
    "speedLimit" to "الحد القانوني للسرعة",
    "warningThreshold" to "حد التنبيه",
    "captureThreshold" to "حد التقاط الدليل",
    "minimumConfidence" to "الحد الأدنى لثقة الرؤية",
    "kmh" to "كم/س",
    "rulesValuesDescription" to "القيم يحددها المشغّل وتحفظ محليًا، وسيستخدمها محرك التحليل عند ربطه.",
    "enforcementActions" to "إجراءات المخالفة",
    "captureOnViolation" to "التقاط دليل المخالفة",
    "createAlertOnViolation" to "إنشاء تنبيه للمشغّل",
    "preserveEvidence" to "حفظ الأدلة المرتبطة",
    "saveRules" to "حفظ القواعد",
    "reset" to "إعادة الضبط",
    "rulesSaved" to "تم حفظ إعدادات قواعد المرور"
)
