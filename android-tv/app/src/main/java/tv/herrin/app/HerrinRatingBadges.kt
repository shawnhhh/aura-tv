package tv.herrin.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/**
 * Movie score chips are explicitly labeled by provider. An absent local IMDb
 * value is hidden, not replaced with TMDB's score or a fabricated zero.
 */
@Composable
internal fun HerrinRatingBadges(film: Film, modifier: Modifier = Modifier) {
    val tmdb = film.ratingValue?.takeIf {
        film.ratingProvider == "TMDB" && it.isFinite() && it in 0.0..10.0
    }
    val imdb = film.imdbRating?.takeIf { it.isFinite() && it in 1.0..10.0 }
    if (tmdb == null && imdb == null) return
    Row(modifier.testTag("herrin-dual-ratings"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (tmdb != null) Row(
            Modifier.background(Color(0x2631B7A5), RoundedCornerShape(9.dp))
                .padding(horizontal = 10.dp, vertical = 7.dp)
                .testTag("tmdb-score"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            // This is the already-bundled official, unmodified TMDB attribution logo.
            Image(painterResource(R.drawable.tmdb_official_logo), "TMDB",
                Modifier.width(38.dp).height(22.dp), contentScale = ContentScale.Fit)
            Text(String.format(Locale.ROOT, "%.1f", tmdb), fontSize = 16.sp,
                color = Color.White, fontWeight = FontWeight.Bold)
        }
        if (imdb != null) Row(
            Modifier.background(Color(0x262C3035), RoundedCornerShape(9.dp))
                .padding(horizontal = 10.dp, vertical = 7.dp)
                .testTag("imdb-score"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text("IMDb", Modifier.background(Color(0xFFF5C518), RoundedCornerShape(3.dp))
                .padding(horizontal = 5.dp, vertical = 3.dp),
                color = Color(0xFF111111), fontSize = 12.sp, fontWeight = FontWeight.Black)
            Text(String.format(Locale.ROOT, "%.1f", imdb), color = Color.White,
                fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}
