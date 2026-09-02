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
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(LANGUAGE, if (value == AppLanguage.ARABIC) "ar" else "en")
            .apply()
    }

    fun setDarkMode(context: Context, enabled: Boolean) {
        darkMode = enabled
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
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
    "monitoringCenter" to "Monitoring Center",
    "systemOnline" to "SYSTEM ONLINE",
    "situationalAwareness" to "Live situational awareness and system health",
    "primaryCamera" to "Primary camera",
    "cameraReady" to "ESP32-CAM is ready for local connection",
    "vehicles" to "Vehicles",
    "averageSpeed" to "Average speed",
    "activeAlerts" to "Active alerts",
    "visionRadar" to "Vision radar",
    "standby" to "Standby",
    "noActiveEvents" to "No active events",
    "capture" to "Capture",
    "cameraControl" to "Camera Control",
    "fullscreen" to "Fullscreen",
    "standard" to "Standard",
    "compact" to "Compact",
    "continueFloating" to "Continue in floating video",
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
    "standbyDescription" to "Ready for a local camera connection"
)

private val ArabicStrings = mapOf(
    "dashboard" to "لوحة القيادة",
    "radar" to "الرادار الذكي",
    "live" to "البث المباشر",
    "alerts" to "التنبيهات",
    "more" to "المزيد",
    "monitoringCenter" to "مركز المراقبة",
    "systemOnline" to "النظام متصل",
    "situationalAwareness" to "وعي لحظي بالحالة وصحة النظام",
    "primaryCamera" to "الكاميرا الرئيسية",
    "cameraReady" to "كاميرا ESP32-CAM جاهزة للاتصال المحلي",
    "vehicles" to "المركبات",
    "averageSpeed" to "متوسط السرعة",
    "activeAlerts" to "التنبيهات النشطة",
    "visionRadar" to "الرادار البصري",
    "standby" to "جاهز",
    "noActiveEvents" to "لا توجد أحداث نشطة",
    "capture" to "التقاط",
    "cameraControl" to "تحكم بالكاميرا",
    "fullscreen" to "ملء الشاشة",
    "standard" to "قياسي",
    "compact" to "مصغّر",
    "continueFloating" to "متابعة الفيديو كنافذة عائمة",
    "incidentReports" to "البلاغات والحوادث",
    "newReport" to "بلاغ حادث جديد",
    "recentReports" to "أحدث البلاغات",
    "incidentType" to "نوع الحادث",
    "location" to "الموقع",
    "plateNumber" to "رقم المركبة / اللوحة",
    "priority" to "الأولوية",
    "details" to "التفاصيل",
    "submitReport" to "إرسال البلاغ",
    "reportHint" to "سجّل الحدث ليتم مراجعته وحفظ الأدلة المرتبطة به.",
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
    "standbyDescription" to "جاهز للاتصال بكاميرا محلية"
)
