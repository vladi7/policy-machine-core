package gov.nist.csd.pm.pdp.audit;

import gov.nist.csd.pm.operations.OperationSet;
import gov.nist.csd.pm.pdp.audit.model.Explain;
import gov.nist.csd.pm.pdp.audit.model.Path;
import gov.nist.csd.pm.pdp.audit.model.PolicyClass;
import gov.nist.csd.pm.exceptions.PMException;
import gov.nist.csd.pm.pdp.decider.PReviewDecider;
import gov.nist.csd.pm.pip.graph.Graph;
import gov.nist.csd.pm.pip.graph.MemGraph;
import gov.nist.csd.pm.pip.graph.model.nodes.Node;
import org.junit.jupiter.api.Test;

import java.util.*;

import static gov.nist.csd.pm.pip.graph.model.nodes.NodeType.*;
import static org.junit.jupiter.api.Assertions.*;

class PReviewAuditorTest {

    @Test
    void testExplain() throws PMException {
        for(TestCases.TestCase tc : TestCases.getTests()) {
            PReviewAuditor auditor = new PReviewAuditor(tc.graph);
            Explain explain = auditor.explain(TestCases.u1ID, TestCases.o1ID);

            assertTrue(explain.getPermissions().containsAll(tc.getExpectedOps()),
                    tc.name + " expected ops " + tc.getExpectedOps() + " but got " + explain.getPermissions());

            PReviewDecider decider = new PReviewDecider(tc.graph);
            Set<String> list = decider.list(TestCases.u1ID, 0, TestCases.o1ID);
            assertEquals(list, explain.getPermissions());

            for (String pcName : tc.expectedPaths.keySet()) {
                List<String> paths = tc.expectedPaths.get(pcName);
                assertNotNull(paths, tc.name);

                PolicyClass pc = explain.getPolicyClasses().get(pcName);
                for (String exPathStr : paths) {
                    boolean match = false;
                    for (Path resPath : pc.getPaths()) {
                        if(pathsMatch(exPathStr, resPath.toString())) {
                            match = true;
                            break;
                        }
                    }
                    assertTrue(match, tc.name + " expected path \"" + exPathStr + "\" but it was not in the results \"" + pc.getPaths() + "\"");
                }
            }
        }
    }

    @Test
    void testExplainAdminOps() throws PMException {
        Graph graph = new MemGraph();
        Node u1 = graph.createNode(1, "u1", U, null);
        Node ua1 = graph.createNode(2, "ua1", UA, null);
        Node o1 = graph.createNode(3, "o1", O, null);
        Node oa1 = graph.createNode(4, "oa1", OA, null);
        Node pc1 = graph.createNode(5, "pc1", PC, null);

        graph.assign(u1.getID(), ua1.getID());
        graph.assign(ua1.getID(), pc1.getID());
        graph.assign(o1.getID(), oa1.getID());
        graph.assign(oa1.getID(), pc1.getID());

        graph.associate(ua1.getID(), oa1.getID(), new OperationSet("read", "assign to"), false);

        PReviewAuditor auditor = new PReviewAuditor(graph);
        Explain explain = auditor.explain(u1.getID(), o1.getID());
        System.out.println(explain.getPermissions());

        PReviewDecider decider = new PReviewDecider(graph);
        System.out.println(decider.list(u1.getID(), 0, o1.getID()));
    }

    private boolean pathsMatch(String expectedStr, String actualStr) {
        String[] expectedArr = expectedStr.split("-");
        String[] actualArr = actualStr.split("-");

        if (expectedArr.length != actualArr.length) {
            return false;
        }

        for (int i = 0; i < expectedArr.length; i++) {
            String ex = expectedArr[i];
            String res = actualArr[i];
            // if the element has brackets, it's a list of permissions
            if (ex.startsWith("[") && res.startsWith("[")) {
                // trim the brackets from the strings
                ex = ex.substring(1, ex.length()-1);
                res = res.substring(1, res.length()-1);

                // split both into an array of strings
                String[] exOps = ex.split(",");
                String[] resOps = res.split(",");

                Arrays.sort(exOps);
                Arrays.sort(resOps);

                if (exOps.length != resOps.length) {
                    return false;
                }
                for (int j = 0; j < exOps.length; j++) {
                    if (!exOps[j].equals(resOps[j])) {
                        return false;
                    }
                }
            } else if (!ex.equals(actualArr[i])) {
                return false;
            }
        }

        return true;
    }
}