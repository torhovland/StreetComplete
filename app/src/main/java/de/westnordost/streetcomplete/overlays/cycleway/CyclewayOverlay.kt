package de.westnordost.streetcomplete.overlays.cycleway

import de.westnordost.countryboundaries.CountryBoundaries
import de.westnordost.streetcomplete.R
import de.westnordost.streetcomplete.data.elementfilter.toElementFilterExpression
import de.westnordost.streetcomplete.data.meta.CountryInfo
import de.westnordost.streetcomplete.data.meta.CountryInfos
import de.westnordost.streetcomplete.data.meta.getByLocation
import de.westnordost.streetcomplete.data.osm.mapdata.Element
import de.westnordost.streetcomplete.data.osm.mapdata.MapDataWithGeometry
import de.westnordost.streetcomplete.data.osm.mapdata.filter
import de.westnordost.streetcomplete.data.user.achievements.EditTypeAchievement
import de.westnordost.streetcomplete.osm.ALL_ROADS
import de.westnordost.streetcomplete.osm.ANYTHING_UNPAVED
import de.westnordost.streetcomplete.osm.cycleway.Cycleway
import de.westnordost.streetcomplete.osm.cycleway.Cycleway.*
import de.westnordost.streetcomplete.osm.cycleway.createCyclewaySides
import de.westnordost.streetcomplete.osm.cycleway.isAmbiguous
import de.westnordost.streetcomplete.osm.cycleway_separate.SeparateCycleway
import de.westnordost.streetcomplete.osm.cycleway_separate.createSeparateCycleway
import de.westnordost.streetcomplete.osm.isPrivateOnFoot
import de.westnordost.streetcomplete.overlays.Color
import de.westnordost.streetcomplete.overlays.Overlay
import de.westnordost.streetcomplete.overlays.PolylineStyle
import de.westnordost.streetcomplete.overlays.StrokeStyle
import de.westnordost.streetcomplete.quests.cycleway.AddCycleway
import java.util.concurrent.FutureTask

class CyclewayOverlay(
    private val countryInfos: CountryInfos,
    private val countryBoundaries: FutureTask<CountryBoundaries>
) : Overlay {

    override val title = R.string.overlay_cycleway
    override val icon = R.drawable.ic_quest_bicycleway
    override val changesetComment = "Specify whether there are cycleways"
    override val wikiLink: String = "Key:cycleway"
    override val achievements = listOf(EditTypeAchievement.BICYCLIST)
    override val hidesQuestTypes = setOf(AddCycleway::class.simpleName!!)

    override fun getStyledElements(mapData: MapDataWithGeometry) =
        // roads
        mapData.filter("""
            ways with
              highway ~ motorway_link|trunk|trunk_link|primary|primary_link|secondary|secondary_link|tertiary|tertiary_link|unclassified|residential|living_street|pedestrian|service
              and area != yes
        """).mapNotNull {
            val pos = mapData.getWayGeometry(it.id)?.center ?: return@mapNotNull null
            val countryInfo = countryInfos.getByLocation(
                countryBoundaries.get(),
                pos.longitude,
                pos.latitude
            )
            it to getStreetCyclewayStyle(it, countryInfo)
        } +
        // separately mapped ways
        mapData.filter("""
            ways with
              highway ~ cycleway|path|footway
              and area != yes
        """).map { it to getSeparateCyclewayStyle(it) }

    override fun createForm(element: Element?) =
        if (element == null) null
        else if (element.tags["highway"] in ALL_ROADS) StreetCyclewayOverlayForm()
        else SeparateCyclewayForm()
}

private fun getSeparateCyclewayStyle(element: Element) =
    PolylineStyle(StrokeStyle(createSeparateCycleway(element.tags).getColor()))

private fun SeparateCycleway?.getColor() = when (this) {
    SeparateCycleway.NONE,
    SeparateCycleway.ALLOWED,
    SeparateCycleway.NON_DESIGNATED ->
        Color.BLACK

    SeparateCycleway.NON_SEGREGATED ->
        Color.CYAN

    SeparateCycleway.SEGREGATED,
    SeparateCycleway.EXCLUSIVE,
    SeparateCycleway.EXCLUSIVE_WITH_SIDEWALK ->
        Color.BLUE

    null ->
        Color.INVISIBLE
}

private fun getStreetCyclewayStyle(element: Element, countryInfo: CountryInfo): PolylineStyle {
    val cycleways = createCyclewaySides(element.tags, countryInfo.isLeftHandTraffic)
    val isCycleStreet = element.tags["bicycle_road"] == "yes" || element.tags["cyclestreet"] == "yes"

    // not set but on road that usually has no cycleway or it is private -> do not highlight as missing
    val isNoCyclewayExpected =
        cycleways == null && (cyclewayTaggingNotExpected(element) || isPrivateOnFoot(element))

    return PolylineStyle(
        stroke = when {
            isCycleStreet ->        StrokeStyle(Color.BLUE)
            isNoCyclewayExpected -> StrokeStyle(Color.INVISIBLE)
            else ->                 null
        },
        strokeLeft = if (isNoCyclewayExpected) null else cycleways?.left.getStyle(countryInfo),
        strokeRight = if (isNoCyclewayExpected) null else cycleways?.right.getStyle(countryInfo)
    )
}

private val cyclewayTaggingNotExpectedFilter by lazy { """
    ways with
      highway ~ living_street|pedestrian|service|motorway_link
      or motorroad = yes
      or expressway = yes
      or maxspeed <= 20
      or cyclestreet = yes
      or bicycle_road = yes
      or surface ~ ${ANYTHING_UNPAVED.joinToString("|")}
""".toElementFilterExpression() }

private fun cyclewayTaggingNotExpected(element: Element) =
    cyclewayTaggingNotExpectedFilter.matches(element)

private fun Cycleway?.getStyle(countryInfo: CountryInfo) = when (this) {
    TRACK, DUAL_TRACK ->
        StrokeStyle(Color.BLUE)

    EXCLUSIVE_LANE, DUAL_LANE, UNSPECIFIED_LANE ->
        if (isAmbiguous(countryInfo)) StrokeStyle(Color.DATA_REQUESTED)
        else                          StrokeStyle(Color.GOLD)

    ADVISORY_LANE, SUGGESTION_LANE, UNSPECIFIED_SHARED_LANE ->
        if (isAmbiguous(countryInfo)) StrokeStyle(Color.DATA_REQUESTED)
        else                          StrokeStyle(Color.ORANGE)

    PICTOGRAMS ->
        StrokeStyle(Color.ORANGE, dashed = true)

    UNKNOWN, INVALID, null, UNKNOWN_LANE, UNKNOWN_SHARED_LANE ->
        StrokeStyle(Color.DATA_REQUESTED)

    BUSWAY ->
        StrokeStyle(Color.LIME, dashed = true)

    SIDEWALK_EXPLICIT ->
        StrokeStyle(Color.CYAN, dashed = true)

    NONE, NONE_NO_ONEWAY ->
        StrokeStyle(Color.BLACK)

    SEPARATE ->
        StrokeStyle(Color.INVISIBLE)
}
