package eu.kanade.tachiyomi.extension.ja.rawlazy

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy

/**
 * RawLazy 扩展
 *
 * 实现 HttpSource 以从 https://rawlazy.io 抓取漫画
 */
class RawLazy : HttpSource() {

    // 源的名称
    override val name = "RawLazy"

    // 网站的基础 URL
    override val baseUrl = "https://rawlazy.io"

    // 内容的语言
    override val lang = "ja"

    // 源是否支持“最新”标签页
    override val supportsLatest = true

    // 用于处理 API 响应的 JSON 解析器
    private val json: Json by injectLazy()

    // HTTP 请求的默认头部
    override fun headersBuilder() = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
        .add("Referer", baseUrl)

    // ==============================
    // 热门漫画（浏览）
    // ==============================

    // 创建热门漫画列表的请求。
    // 该网站使用分页，如 /page/1/, /page/2/ 等。
    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/page/$page/", headers)

    // 解析热门漫画请求的响应。
    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()

        // 使用 CSS 选择器查找所有漫画元素
        val mangas = document.select(popularMangaSelector()).map { element ->
            popularMangaFromElement(element)
        }

        // 检查是否有下一页按钮
        val hasNextPage = document.select(popularMangaNextPageSelector()).isNotEmpty()

        return MangasPage(mangas, hasNextPage)
    }

    // 用于在列表中查找每个漫画容器的 CSS 选择器
    private fun popularMangaSelector() = ".row-of-mangas .col-sm-6"

    // 从单个元素中提取漫画详情（标题、缩略图、URL）
    private fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        val anchor = element.selectFirst("a.thumb")!!
        setUrlWithoutDomain(anchor.attr("href"))
        // 标题通常在锚点的 title 属性中或在 .name a 内部
        title = element.selectFirst(".name a")?.text() ?: anchor.attr("title")
        thumbnail_url = anchor.selectFirst("img")?.attr("src")
    }

    // 用于查找分页“下一页”按钮的 CSS 选择器
    private fun popularMangaNextPageSelector() = "a.next, a:contains(Next)"

    // ==============================
    // 最新更新
    // ==============================

    // 最新更新的网站结构与热门漫画相同
    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // ==============================
    // 搜索
    // ==============================

    // 创建搜索漫画的请求。
    // 使用查询参数 's_manga'
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = GET("$baseUrl/?s_manga=$query", headers)

    // 解析搜索结果与解析热门列表相同
    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // ==============================
    // 漫画详情
    // ==============================

    // 解析漫画详情页面以提取信息，如作者、描述、类型
    override fun mangaDetailsParse(response: Response): SManga = SManga.create().apply {
        val document = response.asJsoup()
        // 定位漫画信息的主要容器
        val infoElement = document.selectFirst(".py-3.py-lg-6.bg-primary .container")

        title = infoElement?.selectFirst("h1.font-bold")?.text() ?: "Unknown"
        author = "Unknown" // 作者信息在页面上不明显
        artist = "Unknown"
        // 提取类型并用逗号连接
        genre = infoElement?.select(".genres-wrap a")?.joinToString { it.text() }
        description = infoElement?.selectFirst(".content-text")?.text()
        status = SManga.UNKNOWN
        thumbnail_url = infoElement?.selectFirst("img.thumb")?.attr("src")
    }

    // ==============================
    // 章节列表
    // ==============================

    // 从漫画详情页面解析章节列表
    override fun chapterListParse(response: Response): List<SChapter> = response.asJsoup().select(chapterListSelector()).map { chapterFromElement(it) }

    private fun chapterListSelector() = ".chapters-list a"

    private fun chapterFromElement(element: Element): SChapter = SChapter.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        name = element.selectFirst("span.font-bold")?.text() ?: element.text()
        // 省略日期解析，因为该网站似乎不标准
    }

    // ==============================
    // 页面列表（图片加载）
    // ==============================

    // 这是复杂的部分。该网站通过 AJAX 请求加载图片。
    // 我们需要模拟 JavaScript 逻辑来获取图片 URL。
    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        // 1. 从页面脚本中提取必要的变量（nonce, ajax_url）
        val script = document.selectFirst("script:containsData(zing.nonce)")?.data()
            ?: throw Exception("未找到 Nonce 脚本")

        val nonce = Regex("""nonce":"(.*?)"""").find(script)?.groupValues?.get(1)
            ?: throw Exception("未找到 Nonce")
        val ajaxUrl = Regex("""ajax_url":"(.*?)"""").find(script)?.groupValues?.get(1)?.replace("\\/", "/")
            ?: throw Exception("未找到 Ajax URL")

        // 2. 提取章节特定的变量（p, chapter_id）
        val chapterScript = document.selectFirst("script:containsData(chapter_id)")?.data()
            ?: throw Exception("未找到章节脚本")

        val p = Regex("""p:\s*(\d+)""").find(chapterScript)?.groupValues?.get(1)
            ?: throw Exception("未找到 Post ID (p)")
        val chapterId = Regex("""chapter_id:\s*'([^']*)'""").find(chapterScript)?.groupValues?.get(1)
            ?: throw Exception("未找到 Chapter ID")

        val pageList = mutableListOf<Page>()
        var imgIndex = 0
        var content = "" // 累积 HTML 内容
        var going = 1 // 继续获取的标志

        // 安全限制以防止出错时无限循环
        var loopCount = 0
        val maxLoops = 20

        // 3. 循环获取所有图片块
        while (going == 1 && loopCount < maxLoops) {
            loopCount++

            // 构建 POST 请求体
            val formBody = FormBody.Builder()
                .add("action", "z_do_ajax")
                .add("_action", "decode_images")
                .add("p", p)
                .add("chapter_id", chapterId)
                .add("img_index", imgIndex.toString())
                .add("content", content) // 将累积的内容发送回服务器
                .add("nonce", nonce)
                .build()

            val request = POST(ajaxUrl, headers, formBody)
            val ajaxResponse = client.newCall(request).execute()

            if (!ajaxResponse.isSuccessful) break

            // 解析 JSON 响应
            val jsonString = ajaxResponse.body.string()
            val jsonObject = json.decodeFromString<JsonObject>(jsonString)

            // 提取包含图片的 HTML 片段
            val mes = jsonObject["mes"]?.jsonPrimitive?.content ?: ""
            // 检查是否还有更多图片需要加载
            going = jsonObject["going"]?.jsonPrimitive?.intOrNull ?: 0

            // 为下一个请求更新 img_index
            imgIndex = jsonObject["img_index"]?.jsonPrimitive?.intOrNull ?: (imgIndex + 1)

            // 累积内容
            content += mes

            // 从接收到的 HTML 片段中解析图片
            val fragment = Jsoup.parseBodyFragment(mes)
            fragment.select("img").forEach { img ->
                val src = img.attr("src")
                // 过滤掉加载指示器
                if (src.isNotEmpty() && !src.contains("load.gif")) {
                    pageList.add(Page(pageList.size, "", src))
                }
            }
        }

        return pageList
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException("未使用")
}
