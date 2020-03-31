package ru.yanus171.feedexfork.parser

import android.content.ContentValues
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import ru.yanus171.feedexfork.Constants
import ru.yanus171.feedexfork.MainApplication
import ru.yanus171.feedexfork.R
import ru.yanus171.feedexfork.provider.FeedData.EntryColumns
import ru.yanus171.feedexfork.provider.FeedData.FeedColumns
import ru.yanus171.feedexfork.service.FetcherService
import ru.yanus171.feedexfork.service.FetcherService.URL_NEXT_PAGE_CLASS_NAME
import ru.yanus171.feedexfork.service.MarkItem
import ru.yanus171.feedexfork.utils.ArticleTextExtractor
import ru.yanus171.feedexfork.utils.ArticleTextExtractor.RemoveHiddenElements
import ru.yanus171.feedexfork.utils.Connection
import ru.yanus171.feedexfork.utils.HtmlUtils
import ru.yanus171.feedexfork.utils.NetworkUtils
import java.net.URL
import java.util.*

const val ONE_WEB_PAGE_TEXT_CLASS_NAME = "oneWebPageTextClassName"
const val ONE_WEB_PAGE_IAMGE_URL_CLASS_NAME = "oneWebPageIconClassName"
const val ONE_WEB_PAGE_DATE_CLASS_NAME = "oneWebPageDateClassName"
const val ONE_WEB_PAGE_AUTHOR_CLASS_NAME = "oneWebPageAuthorClassName"
const val ONE_WEB_PAGE_URL_CLASS_NAME = "oneWebPageUrlClassName"
const val ONE_WEB_PAGE_ARTICLE_CLASS_NAME = "oneWebPageArticleClassName"


object OneWebPageParser {
    fun parse( lastUpdateDate: Long,
               feedID: String,
               feedUrl: String,
               jsonOptions: JSONObject,
               fetchImages: Boolean ): Int {
        var newCount = 0
        val cr = MainApplication.getContext().contentResolver
        val status = FetcherService.Status().Start("Loading OneWebPage", false)
        var urlNextPage = ""
        try { /* check and optionally find favicon */
            try {
                NetworkUtils.retrieveFavicon(MainApplication.getContext(), URL(feedUrl), feedID)
            } catch (ignored: Throwable) {
            }
            var connection: Connection? = null
            val doc: Document
            try {
                connection = Connection(feedUrl)
                doc = Jsoup.parse(connection.inputStream, null, "")
                val articleClassName = jsonOptions.getString(ONE_WEB_PAGE_ARTICLE_CLASS_NAME)
                val textClassName = jsonOptions.getString(ONE_WEB_PAGE_TEXT_CLASS_NAME)
                val authorClassName = jsonOptions.getString(ONE_WEB_PAGE_AUTHOR_CLASS_NAME)
                val dateClassName = jsonOptions.getString(ONE_WEB_PAGE_DATE_CLASS_NAME)
                val imageUrlClassName = jsonOptions.getString(ONE_WEB_PAGE_IAMGE_URL_CLASS_NAME)
                val urlClassName = jsonOptions.getString(ONE_WEB_PAGE_URL_CLASS_NAME)
                val urlNextPageClassName = jsonOptions.getString(URL_NEXT_PAGE_CLASS_NAME)

                val articleList = doc.getElementsByClass(articleClassName)
                val feedEntriesUri = EntryColumns.ENTRIES_FOR_FEED_CONTENT_URI(feedID)
                val feedBaseUrl = NetworkUtils.getBaseUrl(feedUrl)
                val filters = FeedFilters(feedID)
                var now = Date().time
                for (elArticle in articleList) {
                    val author = getValue(authorClassName, elArticle)
                    var date = 0L
                    for ( item in dateClassName.split( " " ) ) {
                        val tempDate = getDate(elArticle, item, now)
                        if ( tempDate > date )
                            date = tempDate
                    }
                    if (date in 1 until lastUpdateDate)
                        continue
                    if ( date == 0L )
                        date = now--

                    val entryUrl = GetUrl(elArticle, urlClassName, "a", "href", feedBaseUrl)
                    val mainImageUrl = GetUrl(elArticle, imageUrlClassName, "img", "src", feedBaseUrl)
                    val textHTML = getValueHTML(textClassName, elArticle)
                    //if ( mainImageUrl.isNotEmpty() )
                    //    textHTML = "<img src='$mainImageUrl'/><p>$textHTML"
                    val improvedContent = HtmlUtils.improveHtmlContent(textHTML, feedBaseUrl, ArticleTextExtractor.MobilizeType.No)

                    // Try to find if the entry is not filtered and need to be processed
                    if (!filters.isEntryFiltered(author, author, entryUrl, improvedContent)) {
                        var isUpdated = false
                        var entryID = 0L
                        val cursor = cr.query(feedEntriesUri, arrayOf(EntryColumns.DATE, EntryColumns._ID), EntryColumns.LINK + Constants.DB_ARG, arrayOf(entryUrl), null)
                        if ( cursor != null ) {
                            if (cursor.moveToFirst()) {
                                entryID = cursor.getLong(1)
                                if (cursor.isNull(0) && cursor.getLong(0) > date)
                                    isUpdated = true
                            }
                            cursor.close()
                        }
                        val values = ContentValues()
                        values.put(EntryColumns.SCROLL_POS, 0)
                        values.put(EntryColumns.TITLE, "")
                        values.put(EntryColumns.ABSTRACT, improvedContent)
                        values.put(EntryColumns.IMAGE_URL, mainImageUrl)
                        values.put(EntryColumns.AUTHOR, author)
                        values.put(EntryColumns.GUID, entryUrl)
                        values.put(EntryColumns.LINK, entryUrl)
                        values.put(EntryColumns.FETCH_DATE, date)
                        values.put(EntryColumns.DATE, date )
                        values.putNull(EntryColumns.MOBILIZED_HTML )

                        if ( isUpdated ) {
                            values.put(EntryColumns.IS_READ, 0)
                            values.put(EntryColumns.IS_NEW, 1)
                            cr.update(EntryColumns.CONTENT_URI(entryID), values, null, null)
                        } else if ( entryID == 0L ) {
                            if (filters.isMarkAsStarred(author, author, entryUrl, improvedContent)) {
                                synchronized(FetcherService.mMarkAsStarredFoundList) { FetcherService.mMarkAsStarredFoundList.add(MarkItem(feedID, author, entryUrl)) }
                                values.put(EntryColumns.IS_FAVORITE, 1)
                            }
                            entryID = cr.insert(feedEntriesUri, values ).lastPathSegment.toLong()
                            newCount++
                        }
                        val imagesToDl = ArrayList<String>()
                        if ( mainImageUrl.isNotEmpty() )
                            imagesToDl.add( mainImageUrl )
                        HtmlUtils.replaceImageURLs(improvedContent, -1, entryUrl, true, imagesToDl)
                        FetcherService.addImagesToDownload(entryID.toString(), imagesToDl)
                    }
                }
                urlNextPage = GetUrl(doc, urlNextPageClassName, "a", "href", feedBaseUrl )
            } catch (e: Exception) {
                FetcherService.Status().SetError(e.localizedMessage, feedID, "", e)
            } finally {
                connection?.disconnect()
            }

            //        synchronized ( FetcherService.mCancelRefresh ) {
//			FetcherService.mCancelRefresh = false;
//		}
            if ( urlNextPage.isNotEmpty() )
                newCount += parse( lastUpdateDate, feedID, urlNextPage, jsonOptions, fetchImages )
            else {
                val values = ContentValues()
                values.put(FeedColumns.LAST_UPDATE, System.currentTimeMillis())
                cr.update(FeedColumns.CONTENT_URI(feedID), values, null, null)
            }
        } finally {
            FetcherService.Status().End(status)
        }
        FetcherService.Status().ChangeProgress( newCount.toString() + " " + MainApplication.getContext().getString( R.string.articleCountLoaded ) )
        return newCount
    }

    private fun getDate(elArticle: Element, dateClassName: String, now: Long): Long {
        var result = 0L
        val list = elArticle.getElementsByClass(dateClassName)
        if ( list.isNotEmpty() )
            for (item in list.first().allElements)
                if (item.hasText()) {
                    try {
                        result = RssAtomParser.parseDate(item.ownText(), now).time
                        break
                    } catch (ignored: Exception) {
                    }
                }
        return result
    }

    fun GetUrl(elArticle: Element, urlClassName: String, tag: String, attrName: String, feedBaseUrl: String): String {
        var result = ""
        val list = elArticle.getElementsByClass(urlClassName)
        if (!list.isEmpty()) {
            val listA = list.first().getElementsByTag(tag)
            if (!listA.isEmpty()) {
                result = listA.first().attr(attrName)
                if (!result.startsWith("http") )
                    result = feedBaseUrl + result
            }
        }
        return result
    }

    private fun getValue(className: String, elArticle: Element): String {
        var result = ""
        if (className.isNotEmpty()) {
            val list = elArticle.getElementsByClass(className)
            if (!list.isEmpty()) result = list.first().text()
        }
        return result
    }
//    private fun getValueOwnText(className: String, elArticle: Element): String {
//        var result = ""
//        if (className.isNotEmpty()) {
//            val list = elArticle.getElementsByClass(className)
//            if (!list.isEmpty()) result = list.first().ownText()
//        }
//        return result
//    }
    private fun getValueHTML(className: String, elArticle: Element): String {
        var result = ""
        if (className.isNotEmpty()) {
            val list = elArticle.getElementsByClass(className)
            if (!list.isEmpty()) {
                RemoveHiddenElements( list.first() )
                result = list.first().html()
            }
        }
        return result
    }
}
