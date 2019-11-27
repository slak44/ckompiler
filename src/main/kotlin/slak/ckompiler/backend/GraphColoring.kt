package slak.ckompiler.backend

/**
 * Maximum cardinality search. Produces a perfect elimination order.
 */
fun maximumCardinalitySearch(graph: AdjLists): List<Int> {
  val q = graph.indices.toMutableList()
  val peo = mutableListOf<Int>()
  val w = MutableList(graph.size) { 0 }
  while (q.isNotEmpty()) {
    val x = q.maxBy { w[it] }!!
    q -= x
    peo += x
    for (y in q.intersect(graph[x])) {
      w[y]++
    }
  }
  return peo
}

private fun AdjLists.neighColorsOf(
    node: Int,
    coloring: List<MachineRegister?>
): List<MachineRegister> {
  return this[node].mapNotNull { coloring[it] }.distinct()
}

/**
 * Generic greedy coloring.
 *
 * Returns null if the graph cannot be colored with the available registers.
 */
fun greedyColoring(
    graph: AdjLists,
    vertexOrder: List<Int>,
    preColoring: Map<Int, MachineRegister>,
    matchColor: (Int, List<MachineRegister>) -> MachineRegister?
): List<MachineRegister>? {
  if (graph.isEmpty()) return emptyList()
  val nodeList = vertexOrder.toMutableList()
  val coloring = MutableList<MachineRegister?>(graph.size) { null }
  for ((node, color) in preColoring.entries) {
    coloring[node] = color
    nodeList -= node
  }
  while (nodeList.isNotEmpty()) {
    val nodeToColor = nodeList.first()
    val neighColors = graph.neighColorsOf(nodeToColor, coloring)
    val color = matchColor(nodeToColor, neighColors) ?: return null
    coloring[nodeToColor] = color
    nodeList -= nodeToColor
  }
  return coloring.map { it!! }
}