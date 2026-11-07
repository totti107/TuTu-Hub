package com.arena.tv.matching

/**
 * Extra names TV guides use for teams. The sports feed already gives us the full name,
 * short name and abbreviation; this table adds nicknames, local-language and Arabic names.
 * Keys are matched against the normalized full team name.
 */
object TeamAliases {
    private val raw: Map<String, List<String>> = mapOf(
        // England
        "manchester united" to listOf("man utd", "man united", "manchester utd", "mufc", "مانشستر يونايتد"),
        "manchester city" to listOf("man city", "mcfc", "مانشستر سيتي"),
        "liverpool" to listOf("lfc", "ليفربول"),
        "arsenal" to listOf("the gunners", "ارسنال", "أرسنال"),
        "chelsea" to listOf("cfc", "تشيلسي"),
        "tottenham hotspur" to listOf("tottenham", "spurs", "توتنهام"),
        "newcastle united" to listOf("newcastle", "newcastle utd", "نيوكاسل"),
        "west ham united" to listOf("west ham", "وست هام"),
        "aston villa" to listOf("villa", "استون فيلا"),
        "brighton and hove albion" to listOf("brighton", "برايتون"),
        "wolverhampton wanderers" to listOf("wolves", "wolverhampton", "ولفرهامبتون"),
        "nottingham forest" to listOf("nottm forest", "notts forest", "forest", "نوتنغهام فورست"),
        "afc bournemouth" to listOf("bournemouth", "بورنموث"),
        "crystal palace" to listOf("palace", "كريستال بالاس"),
        "everton" to listOf("ايفرتون", "إيفرتون"),
        "fulham" to listOf("فولهام"),
        "brentford" to listOf("برينتفورد"),
        "leeds united" to listOf("leeds", "ليدز"),
        "leicester city" to listOf("leicester", "ليستر"),
        "sheffield united" to listOf("sheffield utd", "sheff utd"),
        "sunderland" to listOf("سندرلاند"),
        "burnley" to listOf("بيرنلي"),
        "ipswich town" to listOf("ipswich"),
        "southampton" to listOf("saints", "ساوثهامبتون"),
        // Spain
        "real madrid" to listOf("r madrid", "real", "ريال مدريد"),
        "barcelona" to listOf("barca", "fc barcelona", "برشلونة"),
        "atletico madrid" to listOf("atletico", "atleti", "atl madrid", "at madrid", "اتلتيكو مدريد", "أتلتيكو مدريد"),
        "sevilla" to listOf("sevilla fc", "اشبيلية"),
        "real betis" to listOf("betis", "ريال بيتيس"),
        "real sociedad" to listOf("la real", "sociedad", "ريال سوسيداد"),
        "athletic club" to listOf("athletic bilbao", "bilbao", "athletic", "اتلتيك بلباو"),
        "villarreal" to listOf("فياريال"),
        "valencia" to listOf("فالنسيا"),
        "celta vigo" to listOf("celta", "سيلتا فيغو"),
        "rayo vallecano" to listOf("rayo"),
        "deportivo alaves" to listOf("alaves"),
        // Italy
        "internazionale" to listOf("inter", "inter milan", "inter milano", "انتر ميلان", "إنتر ميلان"),
        "ac milan" to listOf("milan", "ميلان"),
        "juventus" to listOf("juve", "يوفنتوس"),
        "napoli" to listOf("ssc napoli", "نابولي"),
        "as roma" to listOf("roma", "روما"),
        "lazio" to listOf("ss lazio", "لاتسيو"),
        "atalanta" to listOf("اتالانتا", "أتالانتا"),
        "fiorentina" to listOf("فيورنتينا"),
        "bologna" to listOf("بولونيا"),
        "torino" to listOf("تورينو"),
        // Germany
        "bayern munich" to listOf("bayern", "bayern munchen", "fc bayern", "بايرن ميونخ"),
        "borussia dortmund" to listOf("dortmund", "bvb", "دورتموند"),
        "bayer leverkusen" to listOf("leverkusen", "باير ليفركوزن"),
        "rb leipzig" to listOf("leipzig", "لايبزيغ"),
        "eintracht frankfurt" to listOf("frankfurt", "فرانكفورت"),
        "borussia monchengladbach" to listOf("gladbach", "monchengladbach", "mgladbach"),
        "vfb stuttgart" to listOf("stuttgart"),
        "vfl wolfsburg" to listOf("wolfsburg"),
        "sc freiburg" to listOf("freiburg"),
        "1 fc union berlin" to listOf("union berlin"),
        "werder bremen" to listOf("bremen"),
        "tsg hoffenheim" to listOf("hoffenheim"),
        // France
        "paris saint germain" to listOf("psg", "paris sg", "paris", "باريس سان جيرمان"),
        "marseille" to listOf("om", "olympique marseille", "olympique de marseille", "مارسيليا"),
        "lyon" to listOf("ol", "olympique lyonnais", "olympique lyon", "ليون"),
        "monaco" to listOf("as monaco", "موناكو"),
        "lille" to listOf("losc", "ليل"),
        "nice" to listOf("ogc nice", "نيس"),
        "lens" to listOf("rc lens"),
        "rennes" to listOf("stade rennais"),
        // Portugal / Netherlands / Turkey / Scotland
        "benfica" to listOf("sl benfica", "بنفيكا"),
        "porto" to listOf("fc porto", "بورتو"),
        "sporting cp" to listOf("sporting", "sporting lisbon", "سبورتنغ لشبونة"),
        "ajax amsterdam" to listOf("ajax", "اياكس"),
        "psv eindhoven" to listOf("psv"),
        "feyenoord rotterdam" to listOf("feyenoord"),
        "galatasaray" to listOf("gala", "جالطة سراي", "غلطة سراي"),
        "fenerbahce" to listOf("فنربخشة"),
        "besiktas" to listOf("بشكتاش"),
        "celtic" to listOf("سلتيك"),
        "rangers" to listOf("glasgow rangers"),
        // Saudi
        "al hilal" to listOf("alhilal", "hilal", "الهلال"),
        "al nassr" to listOf("alnassr", "nassr", "al nasr", "النصر"),
        "al ittihad" to listOf("ittihad", "al ittihad jeddah", "الاتحاد"),
        "al ahli" to listOf("al ahli saudi", "al ahli jeddah", "الاهلي"),
        "al shabab" to listOf("الشباب"),
        "al ettifaq" to listOf("ettifaq", "الاتفاق"),
        "al qadsiah" to listOf("qadsiah", "القادسية"),
        // MLS
        "inter miami cf" to listOf("inter miami", "miami", "انتر ميامي"),
        "la galaxy" to listOf("galaxy", "los angeles galaxy"),
        "los angeles fc" to listOf("lafc"),
        "new york city fc" to listOf("nycfc", "nyc fc"),
        "new york red bulls" to listOf("ny red bulls", "red bulls"),
        // National teams
        "united states" to listOf("usa", "usmnt", "us"),
        "england" to listOf("انجلترا", "إنجلترا"),
        "saudi arabia" to listOf("ksa", "السعودية"),
        "morocco" to listOf("المغرب"),
        "egypt" to listOf("مصر"),
        "netherlands" to listOf("holland"),
        "south korea" to listOf("korea republic", "korea"),
        "ivory coast" to listOf("cote d ivoire"),
        // NBA / NFL / NHL / MLB nicknames commonly used alone are already covered by
        // the feed's short name ("Lakers", "Chiefs"). A few extras:
        "los angeles lakers" to listOf("la lakers"),
        "los angeles clippers" to listOf("la clippers"),
        "philadelphia 76ers" to listOf("sixers", "76ers"),
        "portland trail blazers" to listOf("blazers"),
        "minnesota timberwolves" to listOf("wolves", "twolves"),
        "oklahoma city thunder" to listOf("okc", "okc thunder"),
        "new england patriots" to listOf("pats"),
        "san francisco 49ers" to listOf("niners", "49ers"),
        "tampa bay buccaneers" to listOf("bucs"),
        "washington commanders" to listOf("commanders"),
        "las vegas raiders" to listOf("raiders"),
        "vegas golden knights" to listOf("golden knights"),
        "new york yankees" to listOf("yankees", "nyy"),
        "boston red sox" to listOf("red sox"),
        "los angeles dodgers" to listOf("dodgers", "la dodgers"),
        "chicago white sox" to listOf("white sox"),
        "toronto blue jays" to listOf("blue jays", "jays"),
    )

    private val table: Map<String, List<String>> =
        raw.entries.associate { (k, v) -> Text.normalize(k) to v.map(Text::normalize) }

    /** Every multi-word name in the table, used to stop "Real" matching inside "Real Betis". */
    val allMultiWordNames: Set<String> by lazy {
        (table.keys + table.values.flatten()).filter { it.contains(' ') }.toSet()
    }

    /** Club-name filler words that TV guides often drop. */
    private val filler = setOf(
        "fc", "afc", "cf", "sc", "ac", "as", "ss", "ssc", "sl", "cd", "ud", "sd", "rc", "rcd", "ca",
        "club", "de", "futbol", "calcio", "sv", "vfb", "vfl", "tsg", "fk", "nk", "sk", "if", "bk",
        "1", "04", "05", "1899", "1846", "1909", "1860", "hotspur", "albion", "and", "hove", "the"
    )

    /**
     * A name a team may appear under in a TV guide.
     * [strict] names are short or ambiguous ("real", "inter", "psg", "lal"): they only count when they
     * stand alone next to "vs" / start / end of the title, so "Real Betis" never matches "Real" (Madrid).
     */
    data class Alias(val text: String, val strict: Boolean)

    /**
     * All normalized names a team may appear under in a TV guide.
     * @param names full name, short name, nickname etc. from the sports feed
     * @param abbreviation 2-4 letter code ("LAL", "MUN"); only used as a whole, isolated word
     */
    fun aliasesFor(names: List<String>, abbreviation: String?): List<Alias> {
        val fromFeed = LinkedHashSet<String>()
        val extra = LinkedHashSet<String>()
        for (n in names) {
            val norm = Text.normalize(n)
            if (norm.isEmpty()) continue
            fromFeed += norm
            table[norm]?.let(extra::addAll)
            val stripped = Text.tokens(norm).filter { it !in filler }.joinToString(" ")
            if (stripped.length >= 4 && stripped != norm) {
                extra += stripped
                table[stripped]?.let(extra::addAll)
            }
        }
        // Reverse lookup: the feed gave us an alias ("Man United") - add the canonical entry too.
        for ((canon, aliases) in table) {
            if ((fromFeed + extra).any { it == canon || it in aliases }) { extra += canon; extra += aliases }
        }
        val out = LinkedHashMap<String, Boolean>()
        for (a in fromFeed) if (a.length >= 3) out[a] = a.length <= 3
        for (a in extra) if (a.length >= 2 && a !in out) out[a] = !a.contains(' ') || a.length <= 3
        abbreviation?.let { val a = Text.normalize(it); if (a.length >= 3 && a !in out) out[a] = true }
        return out.map { (t, strict) -> Alias(t, strict) }
    }
}
