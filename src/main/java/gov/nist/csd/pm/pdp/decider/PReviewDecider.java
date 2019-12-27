package gov.nist.csd.pm.pdp.decider;

import gov.nist.csd.pm.exceptions.PMException;
import gov.nist.csd.pm.operations.OperationSet;
import gov.nist.csd.pm.pip.graph.Graph;
import gov.nist.csd.pm.pip.graph.dag.propagator.Propagator;
import gov.nist.csd.pm.pip.graph.dag.searcher.BreadthFirstSearcher;
import gov.nist.csd.pm.pip.graph.dag.searcher.DepthFirstSearcher;
import gov.nist.csd.pm.pip.graph.dag.visitor.Visitor;
import gov.nist.csd.pm.pip.graph.model.nodes.Node;
import gov.nist.csd.pm.pip.graph.model.nodes.NodeType;
import gov.nist.csd.pm.pip.graph.model.relationships.Association;
import gov.nist.csd.pm.pip.prohibitions.MemProhibitions;
import gov.nist.csd.pm.pip.prohibitions.Prohibitions;
import gov.nist.csd.pm.pip.prohibitions.model.Prohibition;

import java.util.*;

import static gov.nist.csd.pm.pip.graph.model.nodes.NodeType.*;

/**
 * An implementation of the Decider interface that uses an in memory NGAC graph
 */
public class PReviewDecider implements Decider {

    public static final String ANY_OPERATIONS = "any";
    public static final String ALL_OPERATIONS = "*";

    private Graph graph;
    private Prohibitions prohibitions;

    public PReviewDecider(Graph graph) {
        if (graph == null) {
            throw new IllegalArgumentException("NGAC graph cannot be null");
        }

        this.graph = graph;
        this.prohibitions = new MemProhibitions();
    }

    public PReviewDecider(Graph graph, Prohibitions prohibitions) {
        if (graph == null) {
            throw new IllegalArgumentException("NGAC graph cannot be null");
        }

        if (prohibitions == null) {
            prohibitions = new MemProhibitions();
        }

        this.graph = graph;
        this.prohibitions = prohibitions;
    }

    @Override
    public boolean check(long subjectID, long processID, long targetID, String... perms) throws PMException {
        List<String> permsToCheck = Arrays.asList(perms);
        Set<String> permissions = list(subjectID, processID, targetID);

        //if just checking for any operations, return true if the resulting permissions set is not empty.
        //if the resulting permissions set contains * or all operations, return true.
        //if neither of the above apply, return true iff the resulting permissions set contains all the provided
        // permissions to check for
        if (permsToCheck.contains(ANY_OPERATIONS)) {
            return !permissions.isEmpty();
        }
        else if (permissions.contains(ALL_OPERATIONS)) {
            return true;
        }
        else if (permissions.isEmpty()) {
            return false;
        }
        else {
            return permissions.containsAll(permsToCheck);
        }
    }

    @Override
    public Set<String> list(long subjectID, long processID, long targetID) throws PMException {
        Set<String> perms = new HashSet<>();

        // traverse the user side of the graph to get the associations
        UserContext userCtx = processUserDAG(subjectID, processID);
        if (userCtx.getBorderTargets().isEmpty()) {
            return perms;
        }

        // traverse the target side of the graph to get permissions per policy class
        TargetContext targetCtx = processTargetDAG(targetID, userCtx);

        // resolve the permissions
        return resolvePermissions(userCtx, targetCtx);
    }

    @Override
    public Collection<Long> filter(long subjectID, long processID, Collection<Long> nodes, String... perms) {
        nodes.removeIf(n -> {
            try {
                return !check(subjectID, processID, n, perms);
            }
            catch (PMException e) {
                return true;
            }
        });
        return nodes;
    }

    @Override
    public Collection<Long> getChildren(long subjectID, long processID, long targetID, String... perms) throws PMException {
        Set<Long> children = graph.getChildren(targetID);
        return filter(subjectID, processID, children, perms);
    }

    @Override
    public synchronized Map<Long, Set<String>> getAccessibleNodes(long subjectID, long processID) throws PMException {
        Map<Long, Set<String>> results = new HashMap<>();

        //get border nodes.  Can be OA or UA.  Return empty set if no OAs are reachable
        UserContext userCtx = processUserDAG(subjectID, processID);
        if (userCtx.getBorderTargets().isEmpty()) {
            return results;
        }

        for(Long borderTargetID : userCtx.getBorderTargets().keySet()) {
            Set<Long> objects = getAscendants(borderTargetID);
            for (Long objectID : objects) {
                // run dfs on the object
                TargetContext targetCtx = processTargetDAG(objectID, userCtx);

                HashSet<String> permissions = resolvePermissions(userCtx, targetCtx);
                results.put(objectID, permissions);
            }
        }

        return results;
    }

    @Override
    public Map<Long, OperationSet> generateACL(long oaID, long processID) {
        Map<Long, OperationSet> currNodes = new HashMap<>();
        try {
            Map<Long, Association> targetAssociations = graph.getTargetAssociations(oaID);
            for (long id: targetAssociations.keySet()) {
                generateACLRecursiveHelper(id, targetAssociations.get(id).getOperations(), targetAssociations, currNodes);
            }
        } catch (PMException e) {
            System.out.println(e.getMessage());
            e.printStackTrace();
        }
        return currNodes;
    }

    private void generateACLRecursiveHelper (long id, OperationSet perms, Map<Long, Association> targetAssociations, Map<Long, OperationSet> nodesWPerms) throws PMException {
        if (nodesWPerms.get(id) == null) {
            nodesWPerms.put(id, perms);
            for (Long childID: graph.getChildren(id)) {
                Association fromAssoc = targetAssociations.get(childID);
                if (fromAssoc != null) {
                    perms.addAll(fromAssoc.getOperations());
                }
//                System.out.println(childPerms);
                generateACLRecursiveHelper(childID, perms, targetAssociations, nodesWPerms);
            }
        }
    }

    private HashSet<String> resolvePermissions(UserContext userContext, TargetContext targetCtx) throws PMException {
        Map<Long, AssociationContext> pcMap = targetCtx.getPcSet();

        HashSet<String> inter = new HashSet<>();
        boolean first = true;
        for (long pc : pcMap.keySet()) {
            AssociationContext associationContext = pcMap.get(pc);
            OperationSet ops = associationContext.getAll();
            if(first) {
                inter.addAll(ops);
                first = false;
            } else {
                if (inter.contains(ALL_OPERATIONS)) {
                    // clear all of the existing permissions because the intersection already had *
                    // all permissions can be added
                    inter.clear();
                    inter.addAll(ops);
                } else {
                    // if the ops for the pc are empty then the user has no permissions on the target
                    if (ops.isEmpty()) {
                        inter.clear();
                        break;
                    } else if (!ops.contains(ALL_OPERATIONS)) {
                        inter.retainAll(ops);
                    }
                }
            }
        }

        // remove any prohibited operations
        Set<String> denied = resolveProhibitions(userContext, targetCtx);
        inter.removeAll(denied);

        // if the permission set includes *, ignore all other permissions
        if (inter.contains(ALL_OPERATIONS)) {
            inter.clear();
            inter.add(ALL_OPERATIONS);
        }

        return inter;
    }

    private Set<String> resolveProhibitions(UserContext userCtx, TargetContext targetCtx) {
        Set<String> denied = new HashSet<>();

        Set<Prohibition> prohibitions = userCtx.getProhibitions();
        Map<Prohibition, Set<Long>> reachedProhibitedTargets = targetCtx.getReachedProhibitedTargets();

        for(Prohibition p : prohibitions) {
            boolean inter = p.isIntersection();
            List<Prohibition.Node> nodes = p.getNodes();
            Set<Long> reachedTargets = reachedProhibitedTargets.getOrDefault(p, new HashSet<>());

            boolean addOps = false;
            for (Prohibition.Node n : nodes) {
                if (!n.isComplement() && reachedTargets.contains(n.getID()) ||
                        n.isComplement() && !reachedTargets.contains(n.getID())) {
                    addOps = true;
                } else {
                    // since the intersection requires the target to satisfy each node condition in the prohibition
                    // if one is not satisfied then the whole is not satisfied
                    addOps = false;

                    // if the prohibition is the intersection, one unsatisfied container condition means the whole
                    // prohibition is not satisfied
                    if (inter) {
                        break;
                    }
                }
            }

            if (addOps) {
                denied.addAll(p.getOperations());
            }
        }
        return denied;
    }

    /**
     * Perform a depth first search on the object side of the graph.  Start at the target node and recursively visit nodes
     * until a policy class is reached.  On each node visited, collect any operation the user has on the target. At the
     * end of each dfs iteration the visitedNodes map will contain the operations the user is permitted on the target under
     * each policy class.
     *
     * @param targetID      the ID of the current target node.
     */
    private TargetContext processTargetDAG(long targetID, UserContext userCtx) throws PMException {
        Map<Long, AssociationContext> borderTargets = userCtx.getBorderTargets();
        Map<Long, List<Prohibition>> prohibitedTargets = userCtx.getProhibitedTargets();

        Map<Long, Map<Long, AssociationContext>> visitedNodes = new HashMap<>();
        Map<Prohibition, Set<Long>> reachedProhibitedTargets = new HashMap<>();

        Visitor visitor = node -> {
            System.out.println("visiting " + node.getName());
            // add this node to reached prohibited targets if it has any prohibitions
            // ignore the current node if it is the target of the traversal
            if(prohibitedTargets.containsKey(node.getID()) && node.getID() != targetID) {
                List<Prohibition> pros = prohibitedTargets.get(node.getID());
                for(Prohibition p : pros) {
                    Set<Long> r = reachedProhibitedTargets.getOrDefault(p, new HashSet<>());
                    r.add(node.getID());

                    reachedProhibitedTargets.put(p, r);
                }
            }

            Map<Long, AssociationContext> nodeCtx = visitedNodes.getOrDefault(node.getID(), new HashMap<>());
            if (nodeCtx.isEmpty()) {
                visitedNodes.put(node.getID(), nodeCtx);
            }

            if (node.getType().equals(NodeType.PC)) {
                nodeCtx.put(node.getID(), new AssociationContext());
            } else {
                if (borderTargets.containsKey(node.getID())) {
                    AssociationContext uaOpsCtx = borderTargets.get(node.getID());
                    for (Long pc : nodeCtx.keySet()) {
                        AssociationContext pcCtx = nodeCtx.getOrDefault(pc, new AssociationContext());
                        pcCtx.addRecursive(uaOpsCtx.getRecursive());
                        pcCtx.addNonRecursive(uaOpsCtx.getNonRecursive());

                        nodeCtx.put(pc, pcCtx);
                    }
                }
            }
        };

        Propagator propagator = (parent, child) -> {
            System.out.println("propagating " + parent.getName() + " to " + child.getName());
            Map<Long, AssociationContext> parentCtx = visitedNodes.get(parent.getID());
            Map<Long, AssociationContext> nodeCtx = visitedNodes.getOrDefault(child.getID(), new HashMap<>());
            for (Long id : parentCtx.keySet()) {
                AssociationContext ops = nodeCtx.getOrDefault(id, new AssociationContext());
                AssociationContext parentAssocCtx = parentCtx.get(id);

                if (child.getType() == OA || child.getType() == UA) {
                    // propagate recursive and non-recursive admin ops
                    ops.addRecursive(parentAssocCtx.getRecursive().getAdminOps());
                    ops.addNonRecursive(parentAssocCtx.getNonRecursive().getAdminOps());
                    // recursive resource ops;
                    ops.addRecursive(parentAssocCtx.getRecursive().getResourceOps());
                } else {
                    ops.addRecursive(parentAssocCtx.getRecursive().getResourceOps());
                    ops.addNonRecursive(parentAssocCtx.getNonRecursive().getResourceOps());
                }

                System.out.println("propagating " + ops);
                nodeCtx.put(id, ops);
            }
            visitedNodes.put(child.getID(), nodeCtx);
        };

        DepthFirstSearcher searcher = new DepthFirstSearcher(graph);
        searcher.traverse(graph.getNode(targetID), propagator, visitor);

        return new TargetContext(visitedNodes.get(targetID), reachedProhibitedTargets);
    }

    /**
     * Find the target nodes that are reachable by the subject via an association. This is done by a breadth first search
     * starting at the subject node and walking up the user side of the graph until all user attributes the subject is assigned
     * to have been visited.  For each user attribute visited, get the associations it is the source of and store the
     * target of that association as well as the operations in a map. If a target node is reached multiple times, add any
     * new operations to the already existing ones.
     *
     * @return a Map of target nodes that the subject can reach via associations and the operations the user has on each.
     */
    private UserContext processUserDAG(long subjectID, long processID) throws PMException {
        BreadthFirstSearcher searcher = new BreadthFirstSearcher(graph);

        Node start = graph.getNode(subjectID);

        final Map<Long, AssociationContext> borderTargets = new HashMap<>();
        final Set<Prohibition> prohibitions = new HashSet<>();
        final Map<Long, List<Prohibition>> prohibitedTargets = getProhibitionTargets(processID);
        for(Long l : prohibitedTargets.keySet()) {
            prohibitions.addAll(prohibitedTargets.get(l));
        }

        // if the start node is an UA, get it's associations
        if (start.getType() == UA) {
            Map<Long, Association> assocs = graph.getSourceAssociations(start.getID());
            collectAssociations(assocs, borderTargets);
        }

        Visitor visitor = node -> {
            Map<Long, List<Prohibition>> pts = getProhibitionTargets(node.getID());
            for (Long ptsID : pts.keySet()) {
                List<Prohibition> pros = prohibitedTargets.getOrDefault(ptsID, new ArrayList<>());
                pros.addAll(pts.get(ptsID));
                prohibitedTargets.put(ptsID, pros);
            }

            // add any new prohibitions that were reached
            for(Long l : pts.keySet()) {
                prohibitions.addAll(pts.get(l));
            }

            //get the parents of the subject to start bfs on user side
            Set<Long> parents = graph.getParents(node.getID());
            while (!parents.isEmpty()) {
                Long parentNode = parents.iterator().next();

                //get the associations the current parent node is the source of
                Map<Long, Association> assocs = graph.getSourceAssociations(parentNode);

                //collect the target and operation information for each association
                collectAssociations(assocs, borderTargets);

                //add all of the current parent node's parents to the queue
                parents.addAll(graph.getParents(parentNode));

                //remove the current parent from the queue
                parents.remove(parentNode);
            }
        };

        // nothing is being propagated
        Propagator propagator = (parentNode, childNode) -> {};

        // start the bfs
        searcher.traverse(start, propagator, visitor);

        return new UserContext(borderTargets, prohibitedTargets, prohibitions);
    }

    private void collectAssociations(Map<Long, Association> assocs, Map<Long, AssociationContext> borderTargets) {
        for (long targetID : assocs.keySet()) {
            Association association = assocs.get(targetID);
            AssociationContext exAssoc = borderTargets.getOrDefault(targetID, new AssociationContext());

            if (association.isRecursive()) {
                exAssoc.addRecursive(association.getOperations());
            } else {
                exAssoc.addNonRecursive(association.getOperations());
            }

            borderTargets.put(targetID, exAssoc);
        }
    }

    private Map<Long, List<Prohibition>> getProhibitionTargets(long subjectID) throws PMException {
        List<Prohibition> pros = prohibitions.getProhibitionsFor(subjectID);
        Map<Long, List<Prohibition>> prohibitionTargets = new HashMap<>();
        for(Prohibition p : pros) {
            for(Prohibition.Node n : p.getNodes()) {
                List<Prohibition> exPs = prohibitionTargets.getOrDefault(n.getID(), new ArrayList<>());
                exPs.add(p);
                prohibitionTargets.put(n.getID(), exPs);
            }
        }

        return prohibitionTargets;
    }

    private Set<Long> getAscendants(Long vNode) throws PMException {
        Set<Long> ascendants = new HashSet<>();
        ascendants.add(vNode);

        Set<Long> children = graph.getChildren(vNode);
        if (children.isEmpty()) {
            return ascendants;
        }

        ascendants.addAll(children);
        for (Long child : children) {
            ascendants.addAll(getAscendants(child));
        }

        return ascendants;
    }

    private class UserContext {
        private Map<Long, AssociationContext> borderTargets;
        private Map<Long, List<Prohibition>> prohibitedTargets;
        private Set<Prohibition> prohibitions;

        UserContext(Map<Long, AssociationContext> borderTargets, Map<Long, List<Prohibition>> prohibitedTargets, Set<Prohibition> prohibitions) {
            this.borderTargets = borderTargets;
            this.prohibitedTargets = prohibitedTargets;
            this.prohibitions = prohibitions;
        }

        Map<Long, AssociationContext> getBorderTargets() {
            return borderTargets;
        }

        Map<Long, List<Prohibition>> getProhibitedTargets() {
            return prohibitedTargets;
        }

        Set<Prohibition> getProhibitions() {
            return prohibitions;
        }
    }

    private class TargetContext {
        Map<Long, AssociationContext> pcSet;
        Map<Prohibition, Set<Long>> reachedProhibitedTargets;

        public TargetContext(Map<Long, AssociationContext> pcSet, Map<Prohibition, Set<Long>> reachedProhiitedTargets) {
            this.pcSet = pcSet;
            this.reachedProhibitedTargets = reachedProhiitedTargets;
        }

        public Map<Long, AssociationContext> getPcSet() {
            return pcSet;
        }

        public Map<Prohibition, Set<Long>> getReachedProhibitedTargets() {
            return reachedProhibitedTargets;
        }
    }

    private class AssociationContext {
        private OperationSet recursive;
        private OperationSet nonRecursive;

        public AssociationContext() {
            recursive = new OperationSet();
            nonRecursive = new OperationSet();
        }

        public AssociationContext(OperationSet recursive, OperationSet nonRecursive) {
            this.recursive = recursive;
            this.nonRecursive = nonRecursive;
        }

        public OperationSet getRecursive() {
            return recursive;
        }

        public void setRecursive(OperationSet recursive) {
            this.recursive = recursive;
        }

        public void addRecursive(OperationSet recursive) {
            this.recursive.addAll(recursive);
        }

        public OperationSet getNonRecursive() {
            return nonRecursive;
        }

        public void setNonRecursive(OperationSet nonRecursive) {
            this.nonRecursive = nonRecursive;
        }

        public void addNonRecursive(OperationSet nonRecursive) {
            this.nonRecursive.addAll(nonRecursive);
        }

        public OperationSet getAll() {
            OperationSet recSet = this.recursive;
            OperationSet nonRecSet = this.nonRecursive;
            recSet.addAll(nonRecSet);
            return recSet;
        }

        @Override
        public String toString() {
            return "recursive: " + recursive + ", nonrecursive: " + nonRecursive;
        }
    }
}
