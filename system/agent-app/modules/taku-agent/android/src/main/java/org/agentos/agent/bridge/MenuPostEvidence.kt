package org.agentos.agent.bridge

import java.text.Normalizer
import java.util.Locale

internal class MenuPostEvidence(
  venue: String,
  address: String,
  private val dishes: List<String>,
) {
  data class Location(val name: String? = null, val address: String? = null, val city: String? = null)

  val brand = venue.substringBefore('·').trim()
  private val brandName = normalize(brand)
  private val branch = normalize(venue.substringAfter('·', ""))
  private val addressParts = address.split(',').map(::normalize).filter(String::isNotEmpty)
  private val street = streetKeys(address).firstOrNull()
  private val unit = unitNumber(address)
  private val city = if (street == null) addressParts.firstOrNull().orEmpty() else addressParts.getOrNull(1).orEmpty()
  private val needsLocation = branch.isNotEmpty() || street == null && city.isNotEmpty()
  private val branchAfterBrand = Regex("""\b${Regex.escape(brandName)}\s+(?:in|branch at|location at)\s+([\p{L}][\p{L} ]{1,45})""")
  private val explicitBranch = Regex("""\b${Regex.escape(brandName)}\s+([\p{L}][\p{L} ]{1,45}?)\s+(?:branch|location|outlet)\b""")
  private val branchLabel = Regex("""(?im)\b${Regex.escape(brand)}\s*[·|]\s*([\p{L}][\p{L} ]{1,45})\s*$""")
  private val referencedBrand = Regex("""\b(?:at|chez|from) ${Regex.escape(brandName)}\b""")
  private val possessiveBrand = Regex("""\b${Regex.escape(brandName)}(?:s| s)\b""")

  fun matches(
    title: String = "",
    caption: String = "",
    author: String,
    location: Location? = null,
    verifiedAuthor: Boolean = false,
    linkedRestaurant: Regex? = null,
  ): Boolean {
    if (brandName.isEmpty() || !dishes.any { mentions("$title\n$caption", it) }) return false
    val text = "$title\n$caption"
    val normalized = normalize(text)
    if (recipe.containsMatchIn(normalized)) return false
    if (hasConflictingLocation(location, text)) return false
    if (needsLocation && !hasLocationEvidence(location, text)) return false
    if (location?.name?.let(::isRestaurantLocation) == true) return true
    if (verifiedAuthor || isRestaurantLocation(author)) return true
    if (street != null && street in streetKeys(text) && mentions(text, brand)) return true
    return (sequenceOf(title) + paragraphs.splitToSequence(caption)).any { part ->
      if (!dishes.any { mentions(part, it) }) return@any false
      val words = normalize(part)
      linkedRestaurant?.containsMatchIn(part) == true ||
        referencedBrand.containsMatchIn(words) || possessiveBrand.containsMatchIn(words) ||
        mentions(part, brand) && mentions(part, city) ||
        part.lineSequence().any { line -> isRestaurantLocation(line) } ||
        part.split('|', '·', '–', '—').any { isRestaurantLocation(it) }
    }
  }

  private fun hasConflictingLocation(location: Location?, text: String): Boolean {
    val name = location?.name
    if (!name.isNullOrBlank() && !isRestaurantLocation(name) && !isCity(name) &&
      normalize(name) != normalize(location.city.orEmpty())) return true
    val taggedCity = normalize(location?.city.orEmpty())
    if (city.isNotEmpty() && taggedCity.isNotEmpty() && taggedCity != city) return true

    val taggedAddress = location?.address.orEmpty()
    val locations = streetKeys(taggedAddress) + streetKeys(text)
    if (street != null && locations.any { it != street }) return true
    val locationsWithUnit = sequenceOf(taggedAddress) + text.lineSequence().filter { streetKeys(it).isNotEmpty() }
    if (unit != null && locationsWithUnit.mapNotNull(::unitNumber).any { it != unit }) return true

    val explicitPlaces = sequenceOf(branchAfterBrand, explicitBranch).flatMap { pattern ->
      text.lineSequence().flatMap { pattern.findAll(normalize(it)) }.map { it.groupValues[1] }
    }
    val labels = branchLabel.findAll(text).map { it.groupValues[1] }
    if ((city.isNotEmpty() || branch.isNotEmpty()) &&
      (explicitPlaces + labels).any { !mentions(it, city) && !mentions(it, branch) }) return true

    return venuePhrases.findAll(text).any {
      val mention = it.groupValues[1]
      restaurantType.containsMatchIn(normalize(mention)) && !mentions(mention, brand)
    }
  }

  private fun hasLocationEvidence(location: Location?, text: String): Boolean {
    val tagged = listOfNotNull(location?.name, location?.city, location?.address).joinToString(", ")
    if (street != null && (streetKeys(tagged) + streetKeys(text)).any { it == street }) return true
    if (branch.isNotEmpty()) return mentions(tagged, branch) || mentions(text, branch)
    return mentions(tagged, city) || mentions(text, city)
  }

  private fun isCity(value: String): Boolean = city.isNotEmpty() && normalize(value) == city

  private fun isRestaurantLocation(value: String): Boolean {
    val name = normalize(value)
    val suffix = name.removePrefix("$brandName ")
    return isRestaurantName(value) ||
      suffix != name && (restaurantName.matches(suffix) || suffix == branch || suffix == city ||
        city.isNotEmpty() && restaurantName.matches(suffix.removeSuffix(" $city")))
  }

  private fun isRestaurantName(value: String): Boolean = normalize(value) == brandName

  companion object {
    private const val language = "american|canadian|chinese|french|greek|indian|italian|japanese|korean|mexican|thai|vietnamese"
    private const val restaurantTypes = "restaurants?|bistros?|brasseries?|cafes?|bakeries|bakery|delis?|delicatessens?|pizzerias?|bars?|pubs?"
    private val restaurantName = Regex("""(?:(?:$language) )?(?:$restaurantTypes)""")
    private val restaurantType = Regex("""\b(?:$restaurantTypes)\b""")
    private val venuePhrases = Regex(
      """\b(?i:ordered|tried|ate|had|served|dined|dining|eating)(?:\s+[^\r\n.!?;]{1,65}?)?\s+(?:at|chez|from)\s+([\p{Lu}\p{N}][^\r\n.!?;,:#]{0,70})""",
    )
    private val marks = Regex("\\p{M}+")
    private val apostrophes = Regex("['’‘ʼ]")
    private val wordBreaks = Regex("[^\\p{L}\\p{N}]+")
    private val hashtags = Regex("#([\\p{L}\\p{M}\\p{N}_]+)")
    private val paragraphs = Regex("\\r?\\n\\s*\\r?\\n")
    private val recipe = Regex("""\b(?:recipe|how to (?:make|cook|prepare)|cook along|homemade|copycat)\b""")
    private val streets = Regex(
      """\b(\d{1,6}[a-z]?(?: [\p{L}\p{N}]+){1,6}?) (street|st|road|rd|avenue|ave|boulevard|blvd|lane|ln|drive|dr|place|pl|way|court|ct|parkway|pkwy)\b""",
    )
    private val streetTypes = mapOf(
      "street" to "st", "road" to "rd", "avenue" to "ave", "boulevard" to "blvd", "lane" to "ln",
      "drive" to "dr", "place" to "pl", "court" to "ct", "parkway" to "pkwy",
    )
    private val units = Regex("""(?:#\s*|\b(?:unit|suite)\s+)([a-z]?\d+[a-z]?)\b""", RegexOption.IGNORE_CASE)

    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD).lowercase(Locale.ROOT)
      .replace(marks, "").replace(apostrophes, "").replace(wordBreaks, " ").trim()

    private fun mentions(text: String, phrase: String): Boolean {
      val name = normalize(phrase)
      if (name.isEmpty()) return false
      if (" ${normalize(text)} ".contains(" $name ")) return true
      val tag = name.replace(" ", "")
      return hashtags.findAll(text).any { normalize(it.groupValues[1]).replace(" ", "") == tag }
    }

    private fun streetKeys(value: String): List<String> = value.split(',', '\n', ';').flatMap { part ->
      streets.findAll(normalize(part)).map { match ->
        val (name, suffix) = match.destructured
        "$name ${streetTypes[suffix] ?: suffix}"
      }.toList()
    }

    private fun unitNumber(value: String): String? = units.find(value)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
  }
}
