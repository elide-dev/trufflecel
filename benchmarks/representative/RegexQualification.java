package dev.trufflecel;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeUtil;
import java.util.LinkedHashMap;
import java.util.Map;

/** Records the executed regex path outside timing/allocation intervals. */
final class RegexQualification {
  private RegexQualification() {}

  static Map<String, Object> verify(Program program, String workload) {
    int nodes = 0, matches = 0, accelerated = 0;
    for (Node node :
        NodeUtil.findAllNodeInstances(
            ((RootCallTarget) program.callTarget()).getRootNode(), Node.class)) {
      nodes++;
      if (isMatch(node)) {
        matches++;
        if (isAccelerated(node)) accelerated++;
      }
    }
    int expected = workload.equals("map-regex") ? expectedMatches() : 0;
    if (matches != expected || accelerated != expected)
      throw new AssertionError("Wrong regex path: " + workload + "/" + matches + "/" + accelerated);
    var result = new LinkedHashMap<String, Object>();
    result.put("backend", backend());
    result.put("match_nodes", matches);
    result.put("accelerated_nodes", accelerated);
    result.put("ast_nodes", nodes);
    return result;
  }

  static String marker(Map<String, Object> result) {
    return "REGEX_PATH "
        + result.get("backend")
        + " "
        + result.get("match_nodes")
        + " "
        + result.get("accelerated_nodes")
        + " "
        + result.get("ast_nodes")
        + " "
        + result.get("regex_policy")
        + " "
        + result.get("regex_execution")
        + " "
        + result.get("scoped_regexes");
  }

  // Only these four probe methods differ in the candidate overlay. Timed loops are identical.
  private static String backend() {
    return "truffle-regex-re2";
  }

  private static int expectedMatches() {
    return 1;
  }

  private static boolean isMatch(Node node) {
    return node instanceof Regex.MatchNode;
  }

  private static boolean isAccelerated(Node node) {
    return ((Regex.MatchNode) node).isAccelerated();
  }
}
