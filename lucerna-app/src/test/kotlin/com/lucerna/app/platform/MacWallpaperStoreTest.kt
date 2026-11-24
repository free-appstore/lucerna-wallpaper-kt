package com.lucerna.app.platform

import com.dd.plist.BinaryPropertyListWriter
import com.dd.plist.NSArray
import com.dd.plist.NSData
import com.dd.plist.NSDate
import com.dd.plist.NSDictionary
import com.dd.plist.NSString
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 壁纸库补丁的纯逻辑测试：所有 Space / 屏幕都必须被改成新图片，
 * 动态壁纸的 Space 也要换成图片，重复设置时不应产生无谓的写入。
 */
class MacWallpaperStoreTest {

    private val oldUrl = "file:///Users/demo/Library/old.png"
    private val newPath = "/Users/demo/Library/Application Support/Lucerna/renders/new.png"
    private val newUrl = "file:///Users/demo/Library/Application%20Support/Lucerna/renders/new.png"

    @Test
    fun `所有 Space 与屏幕都会改成新图片`() {
        val root = storeFixture()

        val report = MacWallpaperStore.applyToRoot(root, newPath)

        // SystemDefault + 1 块屏幕 + 1 个 Space 的默认配置与分屏配置 + 动态壁纸的 Space
        assertEquals(5, report.total, "桌面配置条目数")
        assertEquals(5, report.changed, "全部条目都应改动")
        assertEquals(
            List(5) { newPath },
            MacWallpaperStore.imagePaths(root),
            "每条配置读回的都是新图片",
        )
    }

    @Test
    fun `动态壁纸的 Space 会换成图片并清掉旧选项`() {
        val root = storeFixture()
        val dynamicContent = dynamicContent(root)

        MacWallpaperStore.applyToRoot(root, newPath)

        val choice = dynamicContent.objectForKey("Choices") as NSArray
        assertEquals(1, choice.count())
        val selected = choice.objectAtIndex(0) as NSDictionary
        assertEquals("com.apple.wallpaper.choice.image", (selected.objectForKey("Provider") as NSString).content)
        assertEquals("\$null", (dynamicContent.objectForKey("EncodedOptionValues") as NSString).content)
        assertEquals("\$null", (dynamicContent.objectForKey("Shuffle") as NSString).content)
    }

    @Test
    fun `已经是同一张图时不重复写入`() {
        val root = storeFixture()

        MacWallpaperStore.applyToRoot(root, newPath)
        val second = MacWallpaperStore.applyToRoot(root, newPath)

        assertEquals(0, second.changed, "内容没变就不该再改")
        assertEquals(5, second.total)
    }

    @Test
    fun `没有桌面配置时报告为空`() {
        val report = MacWallpaperStore.applyToRoot(NSDictionary(), newPath)

        assertEquals(0, report.total)
        assertEquals(0, report.changed)
        assertTrue(MacWallpaperStore.imagePaths(NSDictionary()).isEmpty())
    }

    @Test
    fun `读回图片路径时把 file 地址还原成路径`() {
        val root = storeFixture()

        MacWallpaperStore.applyToRoot(root, newPath)

        assertEquals(newUrl, imageUrl(root))
        assertEquals(
            newPath,
            MacWallpaperStore.imagePaths(root).first(),
            "%20 之类的转义要还原成本地路径",
        )
    }

    @Test
    fun `非图片选择项读不出路径`() {
        val root = NSDictionary().apply {
            put("SystemDefault", NSDictionary().apply { put("Desktop", desktop(dynamicChoice())) })
        }

        assertTrue(MacWallpaperStore.imagePaths(root).isEmpty())
    }

    /** 壁纸库结构：SystemDefault、一块屏幕、一个普通 Space、一个动态壁纸 Space。 */
    private fun storeFixture(): NSDictionary = NSDictionary().apply {
        put("AllSpacesAndDisplays", NSString("\$null"))
        put("SystemDefault", NSDictionary().apply { put("Desktop", desktop(imageChoice(oldUrl))) })
        put("Displays", NSDictionary().apply {
            put("DISPLAY-A", NSDictionary().apply { put("Desktop", desktop(imageChoice(oldUrl))) })
        })
        put("Spaces", NSDictionary().apply {
            put("SPACE-1", NSDictionary().apply {
                put("Default", NSDictionary().apply { put("Desktop", desktop(imageChoice(oldUrl))) })
                put("Displays", NSDictionary().apply {
                    put("DISPLAY-A", NSDictionary().apply { put("Desktop", desktop(imageChoice(oldUrl))) })
                })
            })
            put("SPACE-2", NSDictionary().apply {
                put("Default", NSDictionary().apply { put("Desktop", desktop(dynamicChoice())) })
            })
        })
    }

    private fun dynamicContent(root: NSDictionary): NSDictionary {
        val spaces = root.objectForKey("Spaces") as NSDictionary
        val space = spaces.objectForKey("SPACE-2") as NSDictionary
        val default = space.objectForKey("Default") as NSDictionary
        val desktop = default.objectForKey("Desktop") as NSDictionary
        return desktop.objectForKey("Content") as NSDictionary
    }

    private fun imageUrl(root: NSDictionary): String? {
        val systemDefault = root.objectForKey("SystemDefault") as NSDictionary
        val desktop = systemDefault.objectForKey("Desktop") as NSDictionary
        val content = desktop.objectForKey("Content") as NSDictionary
        val choice = (content.objectForKey("Choices") as NSArray).objectAtIndex(0) as NSDictionary
        val configuration = com.dd.plist.PropertyListParser.parse((choice.objectForKey("Configuration") as NSData).bytes())
        val url = (configuration as NSDictionary).objectForKey("url") as NSDictionary
        return (url.objectForKey("relative") as NSString).content
    }

    private fun desktop(choice: NSDictionary): NSDictionary = NSDictionary().apply {
        put("Content", NSDictionary().apply {
            put("Choices", NSArray(choice))
            put("EncodedOptionValues", NSString("\$null"))
            put("Shuffle", NSString("\$null"))
        })
        put("LastSet", NSDate(Date(0)))
        put("LastUse", NSDate(Date(0)))
    }

    private fun imageChoice(url: String): NSDictionary = NSDictionary().apply {
        put("Provider", NSString("com.apple.wallpaper.choice.image"))
        put("Configuration", configuration(url))
        put("Files", NSArray())
    }

    /** 动态壁纸（航拍）选择项：provider 不同，还带着自己的选项数据。 */
    private fun dynamicChoice(): NSDictionary = NSDictionary().apply {
        put("Provider", NSString("com.apple.wallpaper.choice.aerials"))
        put(
            "Configuration",
            NSData(BinaryPropertyListWriter.writeToArray(NSDictionary().apply {
                put("assetID", NSString("17647EAB-8357-48B0-BCD6-B892194267C5"))
            })),
        )
        put("Files", NSArray())
    }

    private fun configuration(url: String): NSData = NSData(
        BinaryPropertyListWriter.writeToArray(NSDictionary().apply {
            put("type", NSString("imageFile"))
            put("url", NSDictionary().apply { put("relative", NSString(url)) })
        }),
    )
}
