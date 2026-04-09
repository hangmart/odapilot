import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

object Analysis {
    fun computeStats(): List<Map<String, Any>> {
        logger.info { "Analysis.computeStats() — stub" }
        return emptyList()
    }
}
