package gov.nist.csd.pm.pip.graph;

import gov.nist.csd.pm.exceptions.PMException;
import gov.nist.csd.pm.pip.graph.model.nodes.Node;
import gov.nist.csd.pm.pip.graph.model.nodes.NodeType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.*;

import static gov.nist.csd.pm.pip.graph.model.nodes.NodeType.*;
import static org.junit.jupiter.api.Assertions.*;

class GraphSerializerTest {

    private static Graph graph;
    private static long u1ID = 1;
    private static long o1ID = 2;
    private static long ua1ID = 3;
    private static long oa1ID = 4;
    private static long pc1ID = 5;

    @BeforeAll
    static void setUp() throws PMException {
        graph = new MemGraph();

        graph.createNode(u1ID, "u1", U, Node.toProperties("k1", "v1", "k2", "v2"));
        graph.createNode(o1ID, "o1", O, null);
        graph.createNode(ua1ID, "ua1", NodeType.UA, null);
        graph.createNode(oa1ID, "oa1", OA, null);
        graph.createNode(pc1ID, "pc1", NodeType.PC, null);

        graph.assign(u1ID, ua1ID);
        graph.assign(o1ID, oa1ID);
        graph.assign(ua1ID, pc1ID);
        graph.assign(oa1ID, pc1ID);

        graph.associate(ua1ID, oa1ID, new HashSet<>(Arrays.asList("read", "write")));
    }

    @Test
    void testSerialize() throws PMException {
        String actual = Graph.serialize(graph);
        String expected =
                "# nodes\n" +
                "node U u1 {k1=v1,k2=v2}\n" +
                "node O o1 \n" +
                "node UA ua1 \n" +
                "node OA oa1 \n" +
                "node PC pc1 \n" +
                "\n" +
                "# assignments\n" +
                "assign U:u1 UA:ua1\n" +
                "assign O:o1 OA:oa1\n" +
                "assign UA:ua1 PC:pc1\n" +
                "assign OA:oa1 PC:pc1\n" +
                "\n" +
                "# associations\n" +
                "assoc UA:ua1 OA:oa1 read, write";
        assertEquals(expected.replaceAll(" ", ""), actual.trim().replaceAll(" ", ""));

    }

    @Test
    void testDeserialize() throws PMException {
        String str =
                "# nodes\n" +
                        "node U u1 {k1=v1,k2=v2}\n" +
                        "node O o1 \n" +
                        "node UA ua1 \n" +
                        "node OA oa1 \n" +
                        "node PC pc1 \n" +
                        "\n" +
                        "# assignments\n" +
                        "assign U:u1 UA:ua1\n" +
                        "assign O:o1 OA:oa1\n" +
                        "assign UA:ua1 PC:pc1\n" +
                        "assign OA:oa1 PC:pc1\n" +
                        "\n" +
                        "# associations\n" +
                        "assoc UA:ua1 OA:oa1 [read, write]";
        Graph graph = Graph.deserialize(new MemGraph(), str);
        Collection<Node> nodes = graph.getNodes();
        for (Node node : nodes) {
            switch (node.getName()) {
                case "u1":
                    assertEquals(U, node.getType());
                    assertEquals("v1", node.getProperties().get("k1"));
                    assertEquals("v2", node.getProperties().get("k2"));
                    Set<Long> parents = graph.getParents(node.getID());
                    assertEquals(1, parents.size());
                    long p = parents.iterator().next();
                    Node pN = graph.getNode(p);
                    assertEquals("ua1", pN.getName());
                    break;
                case "ua1":
                    assertEquals(UA, node.getType());
                    parents = graph.getParents(node.getID());
                    assertEquals(1, parents.size());
                    p = parents.iterator().next();
                    pN = graph.getNode(p);
                    assertEquals("pc1", pN.getName());
                    Map<Long, Set<String>> sourceAssociations = graph.getSourceAssociations(node.getID());
                    assertEquals(1, sourceAssociations.size());
                    Long tID = sourceAssociations.keySet().iterator().next();
                    Node tN = graph.getNode(tID);
                    assertEquals("oa1", tN.getName());
                    assertTrue(sourceAssociations.get(tID).containsAll(Arrays.asList("read", "write")));
                    break;
                case "o1":
                    assertEquals(O, node.getType());
                    parents = graph.getParents(node.getID());
                    assertEquals(1, parents.size());
                    p = parents.iterator().next();
                    pN = graph.getNode(p);
                    assertEquals("oa1", pN.getName());
                    break;
                case "oa1":
                    assertEquals(OA, node.getType());
                    parents = graph.getParents(node.getID());
                    assertEquals(1, parents.size());
                    p = parents.iterator().next();
                    pN = graph.getNode(p);
                    assertEquals("pc1", pN.getName());
                    break;
                case "pc1":
                    assertEquals(PC, node.getType());
                    parents = graph.getParents(node.getID());
                    assertEquals(0, parents.size());
                    break;
                default:
                    fail(node.toString());
            }
        }
    }
}