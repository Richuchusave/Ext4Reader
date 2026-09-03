package ext4reader.partition

data class PartitionCandidate(
    val index: Int,
    val startLba: Long,
    val endLbaInclusive: Long,
    val typeHint: String,
    val label: String
) {
    fun sizeSectors(): Long = maxOf(0L, endLbaInclusive - startLba + 1)
}

data class ProbeResult(
    val candidate: PartitionCandidate,
    val isExt4: Boolean,
    val detail: String
)
