package com.arena.tv.sports

import com.arena.tv.sports.Region.*
import com.arena.tv.sports.Sport.*

/**
 * Leagues shown in the Sports Hub. `rights` lists who *usually* broadcasts each league per region;
 * it's only a fallback hint for the channel finder (the TV guide and per-game broadcaster list win).
 */
object Leagues {
    val all: List<League> = listOf(
        League(SOCCER, "uefa.champions", "UEFA Champions League", "UCL", listOf("champions league", "ucl", "uefa champions league", "دوري ابطال اوروبا", "ligue des champions", "liga de campeones"),
            mapOf(UK to listOf("TNT Sports", "Amazon Prime"), US to listOf("CBS Sports", "Paramount+"), CA to listOf("DAZN"), MENA to listOf("beIN Sports"),
                ES to listOf("Movistar Liga de Campeones", "Movistar"), FR to listOf("Canal+"), DE to listOf("DAZN", "Amazon Prime"), IT to listOf("Sky Sport", "Amazon Prime"),
                PT to listOf("Sport TV"), NL to listOf("Ziggo Sport"), AU to listOf("Stan Sport"), LATAM to listOf("ESPN", "TNT Sports"))),
        League(SOCCER, "eng.1", "Premier League", "EPL", listOf("premier league", "epl", "english premier league", "الدوري الانجليزي", "الدوري الإنجليزي الممتاز"),
            mapOf(UK to listOf("Sky Sports", "TNT Sports"), US to listOf("USA Network", "NBC", "Peacock"), CA to listOf("FuboTV"), MENA to listOf("beIN Sports"),
                ES to listOf("DAZN"), FR to listOf("Canal+"), DE to listOf("Sky Sport"), IT to listOf("Sky Sport"), PT to listOf("Sport TV"), NL to listOf("Viaplay"),
                AU to listOf("Stan Sport", "Optus Sport"), LATAM to listOf("ESPN"))),
        League(SOCCER, "esp.1", "LaLiga", "LaLiga", listOf("laliga", "la liga", "liga ea sports", "الدوري الاسباني", "الدوري الإسباني"),
            mapOf(UK to listOf("Premier Sports", "LaLigaTV"), US to listOf("ESPN", "ESPN Deportes"), ES to listOf("Movistar LaLiga", "M+ LaLiga", "DAZN"),
                FR to listOf("beIN Sports"), DE to listOf("DAZN"), IT to listOf("DAZN"), LATAM to listOf("ESPN", "Sky Sports"))),
        League(SOCCER, "ita.1", "Serie A", "Serie A", listOf("serie a", "calcio", "الدوري الايطالي"),
            mapOf(UK to listOf("TNT Sports"), US to listOf("CBS Sports", "Paramount+"), IT to listOf("DAZN", "Sky Sport"), FR to listOf("beIN Sports"),
                DE to listOf("DAZN"), ES to listOf("DAZN"), MENA to listOf("Abu Dhabi Sports"))),
        League(SOCCER, "ger.1", "Bundesliga", "BUN", listOf("bundesliga", "الدوري الالماني"),
            mapOf(UK to listOf("Sky Sports"), US to listOf("ESPN"), DE to listOf("Sky Sport", "DAZN"), FR to listOf("beIN Sports"), ES to listOf("Movistar"), IT to listOf("Sky Sport"))),
        League(SOCCER, "fra.1", "Ligue 1", "L1", listOf("ligue 1", "ligue 1 mcdonalds", "الدوري الفرنسي"),
            mapOf(FR to listOf("Ligue 1+", "beIN Sports"), US to listOf("beIN Sports"), UK to listOf("Ligue 1+"), MENA to listOf("beIN Sports"))),
        League(SOCCER, "uefa.europa", "UEFA Europa League", "UEL", listOf("europa league", "uel", "الدوري الاوروبي"),
            mapOf(UK to listOf("TNT Sports"), US to listOf("CBS Sports", "Paramount+"), MENA to listOf("beIN Sports"), ES to listOf("Movistar"), FR to listOf("Canal+"))),
        League(SOCCER, "uefa.europa.conf", "UEFA Conference League", "UECL", listOf("conference league", "uecl"),
            mapOf(UK to listOf("TNT Sports"), US to listOf("Paramount+"), MENA to listOf("beIN Sports"))),
        League(SOCCER, "ksa.1", "Saudi Pro League", "SPL", listOf("saudi pro league", "roshn saudi league", "spl", "دوري روشن", "الدوري السعودي"),
            mapOf(MENA to listOf("Thmanyah", "SSC"), UK to listOf("TNT Sports"), US to listOf("Fox Sports"))),
        League(SOCCER, "usa.1", "MLS", "MLS", listOf("mls", "major league soccer"),
            mapOf(US to listOf("Apple TV", "MLS Season Pass", "Fox"), CA to listOf("Apple TV", "TSN"), UK to listOf("Apple TV"))),
        League(SOCCER, "eng.2", "EFL Championship", "EFL", listOf("championship", "efl championship", "sky bet championship"),
            mapOf(UK to listOf("Sky Sports"), US to listOf("CBS Sports", "Paramount+"))),
        League(SOCCER, "eng.fa", "FA Cup", "FA Cup", listOf("fa cup", "emirates fa cup"),
            mapOf(UK to listOf("BBC", "TNT Sports"), US to listOf("ESPN"), MENA to listOf("beIN Sports"))),
        League(SOCCER, "eng.league_cup", "Carabao Cup", "EFL Cup", listOf("carabao cup", "league cup", "efl cup"),
            mapOf(UK to listOf("Sky Sports"), US to listOf("Paramount+"))),
        League(SOCCER, "esp.copa_del_rey", "Copa del Rey", "Copa", listOf("copa del rey"), defaultOn = false),
        League(SOCCER, "ned.1", "Eredivisie", "ERE", listOf("eredivisie"), mapOf(NL to listOf("ESPN")), defaultOn = false),
        League(SOCCER, "por.1", "Liga Portugal", "LPT", listOf("liga portugal", "primeira liga"), mapOf(PT to listOf("Sport TV")), defaultOn = false),
        League(SOCCER, "tur.1", "Süper Lig", "TSL", listOf("super lig", "trendyol super lig"), defaultOn = false),
        League(SOCCER, "sco.1", "Scottish Premiership", "SPFL", listOf("scottish premiership", "spfl"), mapOf(UK to listOf("Sky Sports")), defaultOn = false),
        League(SOCCER, "mex.1", "Liga MX", "LMX", listOf("liga mx"), mapOf(US to listOf("TUDN", "Univision", "Fox Deportes")), defaultOn = false),
        League(SOCCER, "conmebol.libertadores", "Copa Libertadores", "LIB", listOf("libertadores", "copa libertadores"), defaultOn = false),
        League(SOCCER, "uefa.nations", "UEFA Nations League", "UNL", listOf("nations league"), defaultOn = false),
        League(SOCCER, "fifa.world", "FIFA World Cup", "WC", listOf("world cup", "fifa world cup", "كاس العالم"),
            mapOf(US to listOf("Fox", "FS1", "Telemundo"), UK to listOf("BBC", "ITV"), MENA to listOf("beIN Sports"))),
        League(SOCCER, "fifa.worldq.uefa", "WC Qualifying (Europe)", "WCQ", listOf("world cup qualifier", "world cup qualifiers"), defaultOn = false),
        League(SOCCER, "fifa.friendly", "International Friendly", "FRI", listOf("international friendly", "friendly", "friendlies", "مباراة ودية", "ودية", "amical", "amistoso")),
        League(SOCCER, "fifa.friendly.w", "Women's International Friendly", "FRI-W", listOf("women's friendly"), defaultOn = false, isWomen = true),
        League(SOCCER, "club.friendly", "Club Friendly", "Club FRI", listOf("club friendly", "pre-season friendly", "preseason friendly"), defaultOn = false),

        League(BASKETBALL, "nba", "NBA", "NBA", listOf("nba", "nba basketball"),
            mapOf(US to listOf("ESPN", "ABC", "NBC", "Peacock", "Amazon Prime", "NBA TV"), CA to listOf("TSN", "Sportsnet"), UK to listOf("Sky Sports"),
                MENA to listOf("Abu Dhabi Sports"), AU to listOf("ESPN"), FR to listOf("beIN Sports"))),
        League(BASKETBALL, "wnba", "WNBA", "WNBA", listOf("wnba"), defaultOn = false, isWomen = true),
        League(BASKETBALL, "mens-college-basketball", "College Basketball", "NCAAM", listOf("college basketball", "ncaa basketball", "ncaab"),
            mapOf(US to listOf("ESPN", "CBS", "FS1")), defaultOn = false),

        League(FOOTBALL, "nfl", "NFL", "NFL", listOf("nfl", "nfl football", "sunday night football", "monday night football", "thursday night football"),
            mapOf(US to listOf("CBS", "Fox", "NBC", "ESPN", "ABC", "Amazon Prime", "NFL Network"), CA to listOf("TSN", "CTV", "DAZN"), UK to listOf("Sky Sports"), DE to listOf("RTL", "DAZN"))),
        League(FOOTBALL, "college-football", "College Football", "NCAAF", listOf("college football", "ncaa football", "ncaaf"),
            mapOf(US to listOf("ESPN", "ABC", "CBS", "Fox", "NBC", "FS1")), defaultOn = false),

        League(HOCKEY, "nhl", "NHL", "NHL", listOf("nhl", "nhl hockey"),
            mapOf(US to listOf("ESPN", "TNT", "ABC", "NHL Network"), CA to listOf("Sportsnet", "TVA Sports"))),

        League(BASEBALL, "mlb", "MLB", "MLB", listOf("mlb", "mlb baseball"),
            mapOf(US to listOf("Fox", "FS1", "ESPN", "TBS", "MLB Network"), CA to listOf("Sportsnet"))),

        League(MMA, "ufc", "UFC", "UFC", listOf("ufc", "ufc fight night", "mma"),
            mapOf(US to listOf("Paramount+", "CBS"), UK to listOf("TNT Sports"), MENA to listOf("Abu Dhabi Sports", "TOD"), CA to listOf("Sportsnet"),
                AU to listOf("Foxtel", "Kayo", "Main Event"), LATAM to listOf("ESPN"))),
        League(MMA, "pfl", "PFL", "PFL", listOf("pfl", "professional fighters league"), mapOf(US to listOf("ESPN"), MENA to listOf("MBC Action"))),

        League(TENNIS, "atp", "ATP Tour", "ATP", listOf("atp", "tennis", "atp tour", "masters 1000"),
            mapOf(US to listOf("Tennis Channel"), UK to listOf("Sky Sports Tennis", "Sky Sports"), MENA to listOf("beIN Sports"), ES to listOf("Movistar"),
                FR to listOf("beIN Sports"), DE to listOf("Sky Sport"), IT to listOf("SuperTennis", "Sky Sport"), AU to listOf("beIN Sports"))),
        League(TENNIS, "wta", "WTA Tour", "WTA", listOf("wta", "tennis", "wta tour"),
            mapOf(US to listOf("Tennis Channel"), UK to listOf("Sky Sports Tennis", "Sky Sports"), MENA to listOf("beIN Sports")), isWomen = true),

        League(GOLF, "pga", "PGA Tour", "PGA", listOf("pga tour", "golf", "pga tour golf"),
            mapOf(US to listOf("Golf Channel", "CBS", "NBC", "ESPN+"), UK to listOf("Sky Sports Golf"), MENA to listOf("beIN Sports"), CA to listOf("TSN"),
                DE to listOf("Sky Sport"), AU to listOf("Fox Sports"))),
        League(GOLF, "eur", "DP World Tour", "DPWT", listOf("dp world tour", "european tour", "golf"),
            mapOf(UK to listOf("Sky Sports Golf"), US to listOf("Golf Channel"), MENA to listOf("Abu Dhabi Sports", "beIN Sports"))),
        League(GOLF, "lpga", "LPGA Tour", "LPGA", listOf("lpga", "lpga tour"), mapOf(US to listOf("Golf Channel"), UK to listOf("Sky Sports Golf")), isWomen = true),

        League(RACING, "f1", "Formula 1", "F1", listOf("formula 1", "formula one", "f1", "grand prix"),
            mapOf(UK to listOf("Sky Sports F1"), US to listOf("Apple TV", "ESPN"), MENA to listOf("MBC Action", "SSC"), CA to listOf("TSN", "RDS"),
                DE to listOf("Sky Sport F1"), IT to listOf("Sky Sport F1"), ES to listOf("DAZN F1"), FR to listOf("Canal+"), AU to listOf("Kayo", "Fox Sports"),
                NL to listOf("Viaplay", "F1 TV"), LATAM to listOf("ESPN"))),
        League(RACING, "nascar-premier", "NASCAR Cup Series", "NASCAR", listOf("nascar", "nascar cup series"),
            mapOf(US to listOf("Fox", "FS1", "NBC", "USA Network", "Amazon Prime", "TNT"))),

        League(RUGBY, "180659", "Six Nations", "6N", listOf("six nations", "guinness six nations", "6 nations"),
            mapOf(UK to listOf("BBC", "ITV"), FR to listOf("France 2"), IT to listOf("Sky Sport"), US to listOf("Peacock"))),
        League(RUGBY, "267979", "Premiership Rugby", "PREM", listOf("premiership rugby", "gallagher premiership", "prem rugby"), mapOf(UK to listOf("TNT Sports"))),
        League(RUGBY, "270557", "United Rugby Championship", "URC", listOf("united rugby championship", "urc"), mapOf(UK to listOf("Premier Sports"), MENA to listOf("SuperSport"))),
        League(RUGBY, "242041", "Super Rugby Pacific", "SRP", listOf("super rugby", "super rugby pacific"), mapOf(AU to listOf("Stan Sport"))),
        League(RUGBY, "289234", "Rugby Internationals", "TEST", listOf("rugby union", "test match", "autumn nations series"),
            mapOf(UK to listOf("Amazon Prime", "TNT Sports", "Sky Sports", "BBC", "ITV"))),
        League(RUGBY, "164205", "Rugby World Cup", "RWC", listOf("rugby world cup"), mapOf(UK to listOf("ITV"), FR to listOf("TF1"))),

        League(AUSSIE, "afl", "AFL", "AFL", listOf("afl", "australian football"), mapOf(AU to listOf("Fox Footy", "Seven", "Kayo"))),

        // Every cricket series ESPN is following right now (Tests, ODIs, T20 leagues).
        League(CRICKET, "all", "Cricket", "CRIC", listOf("cricket", "test match", "odi", "t20", "t20i", "ipl", "big bash", "psl", "the hundred"),
            mapOf(UK to listOf("Sky Sports Cricket", "TNT Sports"), US to listOf("Willow", "ESPN+"), CA to listOf("Willow", "Hotstar"),
                MENA to listOf("OSN Sports Cricket", "CricLife"), AU to listOf("Fox Cricket", "Kayo", "Channel 9"), LATAM to listOf("ESPN"))),

        // Weekly shows (no live scores: wrestling is scripted).
        League(WRESTLING, "wwe", "WWE", "WWE", listOf("wwe", "wwe raw", "monday night raw", "smackdown", "wwe smackdown", "nxt", "wwe nxt"),
            mapOf(US to listOf("Netflix", "USA Network", "The CW"), UK to listOf("Netflix"), CA to listOf("Netflix"), MENA to listOf("Netflix", "Shahid"))),
        League(WRESTLING, "aew", "AEW", "AEW", listOf("aew", "aew dynamite", "dynamite", "aew collision", "collision"),
            mapOf(US to listOf("TBS", "TNT", "Max"), UK to listOf("ITV4"), CA to listOf("Sportsnet 360"))),
    )

    /** Country / region a league belongs to, for the "All countries" filter. */
    fun country(l: League): String = when (l.espnId.substringBefore('.')) {
        "eng" -> "England"; "esp" -> "Spain"; "ita" -> "Italy"; "ger" -> "Germany"; "fra" -> "France"
        "uefa" -> "Europe"; "ksa" -> "Saudi Arabia"; "usa" -> "USA"; "mex" -> "Mexico"; "ned" -> "Netherlands"
        "por" -> "Portugal"; "tur" -> "Turkey"; "sco" -> "Scotland"; "conmebol" -> "South America"; "fifa", "club" -> "International"
        else -> when (l.sport) {
            Sport.SOCCER -> "Other"
            Sport.RUGBY -> if (l.espnId == "267979") "England" else "International"
            Sport.AUSSIE -> "Australia"
            Sport.MMA, Sport.TENNIS, Sport.GOLF, Sport.RACING, Sport.CRICKET -> "International"
            else -> "USA"
        }
    }

    private val byKey = all.associateBy { it.key }
    fun byKey(key: String): League? = byKey[key]
    val defaultEnabledKeys: Set<String> = all.filter { it.defaultOn }.map { it.key }.toSet()
}
