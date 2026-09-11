package com.boardgamenation.tracker.data.bgg

import android.util.Xml
import java.io.StringReader
import javax.inject.Inject
import javax.inject.Singleton
import org.xmlpull.v1.XmlPullParser

/**
 * Hand-rolled parsing for the BGG XML API2.
 *
 * A pull parser rather than a binding library, for two reasons. The responses are large
 * and mostly uninteresting — a `thing` response carries dozens of `link` elements, poll
 * results and version histories the app has no use for — so streaming past what we do not
 * want costs nothing. And the API is inconsistent in a way that annotations model badly:
 * `thing` puts values in attributes (`<yearpublished value="1995"/>`) while `collection`
 * puts the same data in element text (`<yearpublished>1995</yearpublished>`). Both cases
 * are handled explicitly below.
 */
@Singleton
class BggXmlParser @Inject constructor() {

    fun parseSearch(xml: String): List<BggSearchResult> {
        val results = mutableListOf<BggSearchResult>()
        forEachItem(xml) { parser, type, id ->
            var name: String? = null
            var year: Int? = null
            walkItem(parser) { child ->
                when (child) {
                    "name" -> if (parser.attr("type") != "alternate" || name == null) {
                        name = parser.attr("value")
                    }

                    "yearpublished" -> year = parser.attr("value")?.toIntOrNull()
                }
            }
            name?.let {
                results += BggSearchResult(
                    bggId = id,
                    name = it,
                    yearPublished = year,
                    isExpansion = type == "boardgameexpansion"
                )
            }
        }
        return results
    }

    fun parseThings(xml: String): List<BggThing> {
        val things = mutableListOf<BggThing>()
        forEachItem(xml) { parser, type, id ->
            var name: String? = null
            var year: Int? = null
            var minPlayers: Int? = null
            var maxPlayers: Int? = null
            var minTime: Int? = null
            var maxTime: Int? = null
            var playingTime: Int? = null
            var weight: Double? = null
            var rating: Double? = null
            var thumbnail: String? = null
            var image: String? = null
            val designers = mutableListOf<String>()
            val publishers = mutableListOf<String>()
            val mechanics = mutableListOf<String>()
            val categories = mutableListOf<String>()
            val expands = mutableListOf<Long>()

            walkItem(parser) { child ->
                when (child) {
                    // The primary name is the one to use; alternates are other languages.
                    "name" -> if (parser.attr("type") == "primary" || name == null) {
                        name = parser.attr("value")
                    }

                    "yearpublished" -> year = parser.attr("value")?.toIntOrNull()

                    "minplayers" -> minPlayers = parser.attr("value")?.toIntOrNull()

                    "maxplayers" -> maxPlayers = parser.attr("value")?.toIntOrNull()

                    "minplaytime" -> minTime = parser.attr("value")?.toIntOrNull()

                    "maxplaytime" -> maxTime = parser.attr("value")?.toIntOrNull()

                    "playingtime" -> playingTime = parser.attr("value")?.toIntOrNull()

                    "thumbnail" -> thumbnail = parser.nextTextOrNull()

                    "image" -> image = parser.nextTextOrNull()

                    "average" -> rating = parser.attr("value")?.toDoubleOrNull()

                    "averageweight" -> weight = parser.attr("value")?.toDoubleOrNull()

                    "link" -> {
                        val value = parser.attr("value")
                        when (parser.attr("type")) {
                            "boardgamedesigner" -> value?.let { designers += it }

                            "boardgamepublisher" -> value?.let { publishers += it }

                            "boardgamemechanic" -> value?.let { mechanics += it }

                            "boardgamecategory" -> value?.let { categories += it }

                            "boardgameexpansion" ->
                                // inbound="true" means "this thing expands that one".
                                if (parser.attr("inbound") == "true") {
                                    parser.attr("id")?.toLongOrNull()?.let { expands += it }
                                }
                        }
                    }
                }
            }

            name?.let {
                things += BggThing(
                    bggId = id,
                    name = it,
                    yearPublished = year,
                    minPlayers = minPlayers,
                    maxPlayers = maxPlayers,
                    minPlaytimeMinutes = minTime ?: playingTime,
                    maxPlaytimeMinutes = maxTime ?: playingTime,
                    weight = weight?.takeIf { w -> w > 0 },
                    rating = rating?.takeIf { r -> r > 0 },
                    designers = designers.distinct(),
                    publishers = publishers.distinct(),
                    mechanics = mechanics.distinct(),
                    categories = categories.distinct(),
                    thumbnailUrl = thumbnail,
                    imageUrl = image,
                    isExpansion = type == "boardgameexpansion",
                    expandsBggIds = expands.distinct()
                )
            }
        }
        return things
    }

    fun parseCollection(xml: String): List<BggCollectionItem> {
        val items = mutableListOf<BggCollectionItem>()
        val parser = newParser(xml)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "item") {
                val id = parser.attr("objectid")?.toLongOrNull()
                val subtype = parser.attr("subtype")
                if (id != null) {
                    var name = ""
                    var year: Int? = null
                    var thumbnail: String? = null
                    var owned = false
                    var forTrade = false
                    var wantToPlay = false
                    var wishlist = false
                    var wishlistPriority: Int? = null
                    var preordered = false
                    var numPlays = 0

                    walkItem(parser) { child ->
                        when (child) {
                            // Collection puts these in element text, not attributes.
                            "name" -> name = parser.nextTextOrNull().orEmpty()

                            "yearpublished" -> year = parser.nextTextOrNull()?.toIntOrNull()

                            "thumbnail" -> thumbnail = parser.nextTextOrNull()

                            "numplays" -> numPlays = parser.nextTextOrNull()?.toIntOrNull() ?: 0

                            "status" -> {
                                owned = parser.attr("own") == "1"
                                forTrade = parser.attr("fortrade") == "1"
                                wantToPlay = parser.attr("wanttoplay") == "1"
                                wishlist = parser.attr("wishlist") == "1"
                                wishlistPriority = parser.attr("wishlistpriority")?.toIntOrNull()
                                preordered = parser.attr("preordered") == "1"
                            }
                        }
                    }

                    if (name.isNotEmpty()) {
                        items += BggCollectionItem(
                            bggId = id,
                            name = name,
                            yearPublished = year,
                            thumbnailUrl = thumbnail,
                            owned = owned,
                            forTrade = forTrade,
                            wantToPlay = wantToPlay,
                            wishlist = wishlist,
                            wishlistPriority = wishlistPriority,
                            preordered = preordered,
                            numPlays = numPlays,
                            isExpansion = subtype == "boardgameexpansion"
                        )
                    }
                }
            }
            event = parser.next()
        }
        return items
    }

    /** BGG answers a queued collection request with a `<message>` body. */
    fun isQueuedResponse(xml: String): Boolean = xml.contains("<message", ignoreCase = true) &&
        xml.contains("request", ignoreCase = true) &&
        !xml.contains("<item", ignoreCase = true)

    fun errorMessage(xml: String): String? {
        val start = xml.indexOf("<message")
        if (start < 0) return null
        val open = xml.indexOf('>', start)
        val close = xml.indexOf("</message>", open)
        if (open < 0 || close < 0) return null
        return xml.substring(open + 1, close).trim().takeIf { it.isNotEmpty() }
    }

    // --- plumbing -------------------------------------------------------------------

    private fun newParser(xml: String): XmlPullParser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        setInput(StringReader(xml))
    }

    private inline fun forEachItem(xml: String, onItem: (XmlPullParser, type: String?, id: Long) -> Unit) {
        val parser = newParser(xml)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "item") {
                val id = parser.attr("id")?.toLongOrNull()
                val type = parser.attr("type")
                if (id != null) onItem(parser, type, id)
            }
            event = parser.next()
        }
    }

    /**
     * Walks the children of the current `item` until its closing tag, calling [onChild]
     * at each start tag. Depth is tracked explicitly because `item` elements nest inside
     * `statistics` and `poll` blocks.
     */
    private inline fun walkItem(parser: XmlPullParser, onChild: (String) -> Unit) {
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    depth++
                    onChild(parser.name)
                }

                XmlPullParser.END_TAG -> depth--

                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    private fun XmlPullParser.attr(name: String): String? = getAttributeValue(null, name)

    private fun XmlPullParser.nextTextOrNull(): String? = try {
        nextText().trim().takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }
}
