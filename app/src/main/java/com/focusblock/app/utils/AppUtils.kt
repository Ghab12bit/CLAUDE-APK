package com.focusblock.app.utils

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build

object AppUtils {

    data class AppInfo(
        val packageName: String,
        val appName: String,
        val icon: Drawable?,
        val isSystemApp: Boolean
    )

    // System apps that are user-facing and should always be shown in app lists
    // These are often pre-installed but users commonly want to block them
    private val USER_FACING_SYSTEM_APPS = setOf(
        "com.android.chrome",
        "com.google.android.youtube",
        "com.google.android.apps.photos",
        "com.google.android.apps.maps",
        "com.google.android.gm",
        "com.google.android.calendar",
        "com.sec.android.app.sbrowser", // Samsung Internet
        "com.samsung.android.game.gamehome", // Samsung Game Launcher
        "com.samsung.android.app.notes", // Samsung Notes
        "com.google.android.apps.youtube.music",
        "com.google.android.googlequicksearchbox" // Google app
    )

    fun getInstalledApps(context: Context, includeSystemApps: Boolean = false): List<AppInfo> {
        val packageManager = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val resolveInfoList = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        }

        return resolveInfoList
            .asSequence()
            .filter { it.activityInfo?.packageName != null }
            .map { resolveInfo ->
                val applicationInfo = resolveInfo.activityInfo.applicationInfo
                val isSystemApp = (applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

                AppInfo(
                    packageName = resolveInfo.activityInfo.packageName,
                    appName = resolveInfo.loadLabel(packageManager).toString(),
                    icon = try {
                        resolveInfo.loadIcon(packageManager)
                    } catch (e: Exception) {
                        null
                    },
                    isSystemApp = isSystemApp
                )
            }
            .filter { includeSystemApps || !it.isSystemApp || USER_FACING_SYSTEM_APPS.contains(it.packageName) }
            .filter { it.packageName != context.packageName } // Exclude FocusBlock itself
            .distinctBy { it.packageName }
            .sortedBy { it.appName.lowercase() }
            .toList()
    }

    fun getAppName(context: Context, packageName: String): String {
        return try {
            val packageManager = context.packageManager
            val applicationInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(packageName, 0)
            }
            packageManager.getApplicationLabel(applicationInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName.substringAfterLast(".")
        }
    }

    fun getAppIcon(context: Context, packageName: String): Drawable? {
        return try {
            context.packageManager.getApplicationIcon(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    fun isAppInstalled(context: Context, packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getApplicationInfo(packageName, 0)
            }
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun launchApp(context: Context, packageName: String): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    fun goToHome(context: Context) {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    // App categories for grouping
    enum class AppCategory(val displayName: String, val icon: String) {
        SOCIAL_MEDIA("Social Media", "people"),
        GAMES("Games", "games"),
        ENTERTAINMENT("Entertainment", "movie"),
        COMMUNICATION("Communication", "chat"),
        SHOPPING("Shopping", "shopping"),
        NEWS("News & Reading", "article"),
        PRODUCTIVITY("Productivity", "work"),
        DATING("Dating", "favorite"),
        BROWSER("Browsers", "web"),
        OTHER("Other", "apps")
    }

    // Package names mapped to categories
    val APP_CATEGORIES: Map<AppCategory, List<String>> = mapOf(
        AppCategory.SOCIAL_MEDIA to listOf(
            "com.instagram.android",
            "com.facebook.katana",
            "com.twitter.android",
            "com.snapchat.android",
            "com.zhiliaoapp.musically", // TikTok
            "com.reddit.frontpage",
            "com.linkedin.android",
            "com.pinterest",
            "com.tumblr",
            "com.vkontakte.android",
            "com.twitter.android.lite",
            "com.instagram.lite",
            "com.facebook.lite",
            "com.ss.android.ugc.trill", // TikTok (alternate)
            "com.tiktok.musical.ly",
            "com.bereal.ft",
            "com.lemon8.android"
        ),
        AppCategory.GAMES to listOf(
            "com.king.candycrushsaga",
            "com.supercell.clashofclans",
            "com.supercell.clashroyale",
            "com.supercell.brawlstars",
            "com.mobile.legends",
            "com.tencent.ig", // PUBG
            "com.activision.callofduty.shooter",
            "com.epicgames.fortnite",
            "com.mojang.minecraftpe",
            "com.miHoYo.GenshinImpact",
            "com.riotgames.league.wildrift",
            "com.ea.gp.fifamobile",
            "com.dts.freefireth",
            "com.garena.game.codm",
            "com.kiloo.subwaysurf",
            "com.imangi.templerun2",
            "io.anuke.mindustry",
            "com.innersloth.spacemafia", // Among Us
            "com.rovio.angrybirds2.revo",
            "com.halfbrick.fruitninjafree"
        ),
        AppCategory.ENTERTAINMENT to listOf(
            "com.google.android.youtube",
            "com.netflix.mediaclient",
            "com.amazon.avod.thirdpartyclient", // Prime Video
            "com.disney.disneyplus",
            "com.hbo.hbonow",
            "com.hulu.plus",
            "com.spotify.music",
            "com.apple.android.music",
            "com.pandora.android",
            "com.soundcloud.android",
            "com.google.android.youtube.tvmusic",
            "tv.twitch.android.app",
            "com.vimeo.android.videoapp",
            "com.crunchyroll.crunchyroid",
            "jp.nicovideo.nicobox",
            "com.ted.android",
            "com.dailymotion.dailymotion"
        ),
        AppCategory.COMMUNICATION to listOf(
            "com.whatsapp",
            "org.telegram.messenger",
            "com.facebook.orca", // Messenger
            "com.discord",
            "com.Slack",
            "jp.naver.line.android",
            "com.viber.voip",
            "com.skype.raider",
            "us.zoom.videomeetings",
            "com.google.android.apps.meetings", // Google Meet
            "com.microsoft.teams",
            "com.snapchat.android",
            "com.imo.android.imoim",
            "org.thoughtcrime.securesms", // Signal
            "com.wire"
        ),
        AppCategory.SHOPPING to listOf(
            "com.amazon.mShop.android.shopping",
            "com.ebay.mobile",
            "com.shopify.mobile",
            "com.alibaba.aliexpresshd",
            "com.flipkart.android",
            "com.myntra.android",
            "com.ubercab.eats",
            "com.application.zomato",
            "com.dd.doordash",
            "com.grubhub.android",
            "com.instacart.client",
            "com.walmart.android",
            "com.target.ui",
            "com.offerup",
            "com.contextlogic.wish"
        ),
        AppCategory.NEWS to listOf(
            "flipboard.app",
            "com.twitter.android",
            "com.google.android.apps.magazines", // Google News
            "com.nytimes.android",
            "com.washingtonpost.android",
            "com.guardian",
            "com.cnn.mobile.android.phone",
            "com.foxnews.android",
            "com.medium.reader",
            "com.quora.android",
            "com.reddit.frontpage",
            "com.ideashower.readitlater.pro", // Pocket
            "com.feedly.android.beta"
        ),
        AppCategory.DATING to listOf(
            "com.tinder",
            "com.bumble.app",
            "com.hinge.app",
            "com.okcupid.okcupid",
            "com.match.android.matchmobile",
            "com.badoo.mobile",
            "com.grindr.android",
            "co.hinge.app",
            "com.coffee.android",
            "com.spark.jayvee"
        ),
        AppCategory.BROWSER to listOf(
            "com.android.chrome",
            "org.mozilla.firefox",
            "com.opera.browser",
            "com.brave.browser",
            "com.sec.android.app.sbrowser", // Samsung Browser
            "com.UCMobile.intl",
            "com.opera.mini.native",
            "com.microsoft.emmx", // Edge
            "com.duckduckgo.mobile.android"
        ),
        AppCategory.PRODUCTIVITY to listOf(
            "com.google.android.gm", // Gmail
            "com.microsoft.office.outlook",
            "com.google.android.apps.docs", // Google Docs
            "com.microsoft.office.word",
            "com.microsoft.office.excel",
            "com.notion.id",
            "com.todoist",
            "com.ticktick.task",
            "com.anydo",
            "com.evernote"
        )
    )

    // Get category for a package name
    fun getAppCategory(packageName: String): AppCategory {
        for ((category, packages) in APP_CATEGORIES) {
            if (packages.contains(packageName)) {
                return category
            }
        }
        return AppCategory.OTHER
    }

    // Get all apps in a category from installed apps
    fun getAppsInCategory(
        apps: List<AppInfo>,
        category: AppCategory
    ): List<AppInfo> {
        val categoryPackages = APP_CATEGORIES[category] ?: emptyList()
        return if (category == AppCategory.OTHER) {
            // For "Other", return apps not in any defined category
            apps.filter { app ->
                APP_CATEGORIES.values.flatten().none { it == app.packageName }
            }
        } else {
            apps.filter { app -> categoryPackages.contains(app.packageName) }
        }
    }

    // Common social media and entertainment apps that users typically want to block
    val SUGGESTED_APPS_TO_BLOCK = APP_CATEGORIES.values.flatten().distinct()
}
