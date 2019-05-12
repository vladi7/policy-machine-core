package gov.nist.csd.pm.audit;

import gov.nist.csd.pm.audit.model.Explain;
import gov.nist.csd.pm.audit.model.Path;
import gov.nist.csd.pm.audit.model.PolicyClass;
import gov.nist.csd.pm.exceptions.PMException;
import gov.nist.csd.pm.graph.Graph;
import gov.nist.csd.pm.graph.GraphSerializer;
import gov.nist.csd.pm.graph.MemGraph;
import gov.nist.csd.pm.graph.dag.propagator.Propagator;
import gov.nist.csd.pm.graph.dag.searcher.DepthFirstSearcher;
import gov.nist.csd.pm.graph.dag.visitor.Visitor;
import gov.nist.csd.pm.graph.model.nodes.Node;

import java.util.*;

import static gov.nist.csd.pm.graph.model.nodes.NodeType.*;

public class PReviewAuditor implements Auditor {

    private static final String ALL_OPERATIONS = "*";

    private Graph graph;

    public PReviewAuditor(Graph graph) {
        this.graph = graph;
    }

    @Override
    public Explain explain(long userID, long targetID) throws PMException {
        Node userNode = graph.getNode(userID);
        Node targetNode = graph.getNode(targetID);

        List<EdgePath> userPaths = dfs(userNode);
        List<EdgePath> targetPaths = dfs(targetNode);

        Map<String, PolicyClass> resolvedPaths = resolvePaths(userPaths, targetPaths, targetID);
        Set<String> perms = resolvePermissions(resolvedPaths);

        return new Explain(perms, resolvedPaths);
    }

    private Set<String> resolvePermissions(Map<String, PolicyClass> paths) {
        Map<String, Set<String>> pcPerms = new HashMap<>();
        for (String pc : paths.keySet()) {
            PolicyClass pcPaths = paths.get(pc);
            for(Path p : pcPaths.getPaths()) {
                Set<String> ops = p.getOperations();
                Set<String> exOps = pcPerms.getOrDefault(pc, new HashSet<>());
                exOps.addAll(ops);
                pcPerms.put(pc, exOps);
            }
        }

        Set<String> perms = new HashSet<>();
        boolean first = true;

        for(String pc : pcPerms.keySet()) {
            Set<String> ops = pcPerms.get(pc);
            if (first) {
                perms.addAll(ops);
                first = false;
            }
            else {
                if (perms.contains(ALL_OPERATIONS)) {
                    perms.remove(ALL_OPERATIONS);
                    perms.addAll(ops);
                }
                else {
                    // if the ops for the pc are empty then the user has no permissions on the target
                    if (ops.isEmpty()) {
                        perms.clear();
                        break;
                    }
                    else if (!ops.contains(ALL_OPERATIONS)) {
                        perms.retainAll(ops);
                    }
                }
            }
        }

        // if the permission set includes *, ignore all other permissions
        if (perms.contains(ALL_OPERATIONS)) {
            perms.clear();
            perms.add(ALL_OPERATIONS);
        }

        return perms;
    }

    /**
     * Given a set of paths starting at a user, and a set of paths starting at an object, return the paths from
     * the user to the target node (through an association) that belong to each policy class. A path is added to a policy
     * class' entry in the returned map if the user path ends in an association in which the target of the association
     * exists in a target path. That same target path must also end in a policy class. If the path does not end in a policy
     * class the target path is ignored.
     *
     * @param userPaths the set of paths starting with a user.
     * @param targetPaths the set of paths starting with a target node.
     * @param targetID the ID of the target node.
     * @return the set of paths from a user to a target node (through an association) for each policy class in the system.
     * @throws PMException if there is an exception traversing the graph
     */
    private Map<String, PolicyClass> resolvePaths(List<EdgePath> userPaths, List<EdgePath> targetPaths, long targetID) throws PMException {
        Map<String, PolicyClass> results = new HashMap<>();

        for (EdgePath targetPath : targetPaths) {
            EdgePath.Edge lastTargetEdge = targetPath.getEdges().get(targetPath.getEdges().size()-1);

            // if the last element in the target path is a pc, the target belongs to that pc, add the pc to the results
            // skip to the next target path if it is not a policy class
            if (lastTargetEdge.getTarget().getType() == PC) {
                if(!results.containsKey(lastTargetEdge.getTarget().getName())) {
                    results.put(lastTargetEdge.getTarget().getName(), new PolicyClass());
                }
            } else {
                continue;
            }

            for(EdgePath userPath : userPaths) {
                EdgePath.Edge lastUserEdge = userPath.getEdges().get(userPath.getEdges().size()-1);

                // if the last edge does not have any ops, it is not an association, so ignore it
                if (lastUserEdge.getOps() == null) {
                    continue;
                }

                for(int i = 0; i < targetPath.getEdges().size(); i++) {
                    EdgePath.Edge e = targetPath.getEdges().get(i);

                    // if the target of the last edge in a user resolvedPath does not match the target of the current edge in the target
                    // resolvedPath, continue to the next target edge
                    if((lastUserEdge.getTarget().getID() != e.getTarget().getID())
                            && (lastUserEdge.getTarget().getID() != e.getSource().getID())) {
                        continue;
                    }

                    List<EdgePath.Edge> pathToTarget = new ArrayList<>();
                    for(int j = 0; j <= i; j++) {
                        pathToTarget.add(targetPath.getEdges().get(j));
                    }

                    ResolvedPath resolvedPath = resolvePath(userPath, pathToTarget, lastTargetEdge);
                    if (resolvedPath == null) {
                        continue;
                    }

                    Path nodePath = resolvedPath.toNodePath(targetID);

                    // check that the resolved resolvedPath does not already exist in the results
                    // this can happen if there is more than one resolvedPath to a UA/OA from a U/O
                    PolicyClass exPC = results.get(resolvedPath.getPc().getName());
                    boolean found = false;
                    for(Path p : exPC.getPaths()) {
                        if(p.equals(nodePath)) {
                            found = true;
                            break;
                        }
                    }
                    if(found) {
                        continue;
                    }

                    // add resolvedPath to policy class' paths
                    exPC.getPaths().add(nodePath);
                    exPC.getOperations().addAll(resolvedPath.getOps());
               }
            }
        }
        return results;
    }

    private ResolvedPath resolvePath(EdgePath userPath, List<EdgePath.Edge> pathToTarget, EdgePath.Edge pcEdge) {
        if (pcEdge.getTarget().getType() != PC) {
            return null;
        }

        // get the operations in this path
        // the operations are the ops of the association in the user path
        Set<String> ops = new HashSet<>();
        for(EdgePath.Edge edge : userPath.getEdges()) {
            if(edge.getOps() != null) {
                ops = edge.getOps();
                break;
            }
        }

        EdgePath path = new EdgePath();
        Collections.reverse(pathToTarget);
        for(EdgePath.Edge edge : userPath.getEdges()) {
            path.addEdge(edge);
        }
        for(EdgePath.Edge edge : pathToTarget) {
            path.addEdge(edge);
        }

        return new ResolvedPath(pcEdge.getTarget(), path, ops);
    }

    private List<EdgePath> dfs(Node start) throws PMException {
        DepthFirstSearcher searcher = new DepthFirstSearcher(graph);

        final List<EdgePath> paths = new ArrayList<>();
        final Map<Long, List<EdgePath>> propPaths = new HashMap<>();

        Visitor visitor = node -> {
            List<EdgePath> nodePaths = new ArrayList<>();

            for(Long parentID : graph.getParents(node.getID())) {
                EdgePath.Edge edge = new EdgePath.Edge(node, graph.getNode(parentID), null);
                List<EdgePath> parentPaths = propPaths.get(parentID);
                if(parentPaths.isEmpty()) {
                    EdgePath path = new EdgePath();
                    path.addEdge(edge);
                    nodePaths.add(0, path);
                } else {
                    for(EdgePath parentPath : parentPaths) {
                        parentPath.getEdges().add(0, edge);
                        nodePaths.add(parentPath);
                    }
                }
            }

            Map<Long, Set<String>> assocs = graph.getSourceAssociations(node.getID());
            for(Long targetID : assocs.keySet()) {
                Set<String> ops = assocs.get(targetID);
                Node targetNode = graph.getNode(targetID);
                EdgePath path = new EdgePath();
                path.addEdge(new EdgePath.Edge(node, targetNode, ops));
                nodePaths.add(path);
            }

            propPaths.put(node.getID(), nodePaths);
        };

        Propagator propagator = (parentNode, childNode) -> {
            List<EdgePath> childPaths = propPaths.computeIfAbsent(childNode.getID(), k -> new ArrayList<>());
            List<EdgePath> parentPaths = propPaths.get(parentNode.getID());
            if(parentPaths.isEmpty() && parentNode.getType() == PC) {
                EdgePath newPath = new EdgePath();
                EdgePath.Edge edge = new EdgePath.Edge(childNode, parentNode, null);
                newPath.getEdges().add(0, edge);
                childPaths.add(newPath);
                propPaths.put(childNode.getID(), childPaths);
            } else {
                for (EdgePath path : parentPaths) {
                    EdgePath newPath = new EdgePath();
                    newPath.getEdges().addAll(path.getEdges());
                    EdgePath.Edge edge = new EdgePath.Edge(childNode, parentNode, null);
                    newPath.getEdges().add(0, edge);
                    childPaths.add(newPath);
                    propPaths.put(childNode.getID(), childPaths);
                }
            }

            if (childNode.getID() == start.getID()) {
                paths.clear();
                paths.addAll(propPaths.get(childNode.getID()));
            }
        };

        searcher.traverse(start, propagator, visitor);
        return paths;
    }

    private static class ResolvedPath {
        private Node pc;
        private EdgePath path;
        private Set<String> ops;

        public ResolvedPath() {

        }

        public ResolvedPath(Node pc, EdgePath path, Set<String> ops) {
            this.pc = pc;
            this.path = path;
            this.ops = ops;
        }

        public Node getPc() {
            return pc;
        }

        public EdgePath getPath() {
            return path;
        }

        public Set<String> getOps() {
            return ops;
        }

        public Path toNodePath(long targetID) {
            Path nodePath = new Path();
            nodePath.setOperations(this.ops);

            if(this.path.getEdges().isEmpty()) {
                return nodePath;
            }

            boolean foundAssoc = false;
            for(EdgePath.Edge edge : this.path.getEdges()) {
                Node node;
                if(!foundAssoc) {
                    node = edge.getTarget();
                } else {
                    node = edge.getSource();
                }

                if(nodePath.getNodes().isEmpty()) {
                    nodePath.getNodes().add(edge.getSource());
                }

                nodePath.getNodes().add(node);

                if(edge.getOps() != null) {
                    foundAssoc = true;
                    if(edge.getTarget().getID() == targetID) {
                        return nodePath;
                    }
                }
            }

            return nodePath;
        }
    }

    private static class EdgePath {
        private List<Edge> edges;

        public EdgePath() {
            this.edges = new ArrayList<>();
        }

        public List<Edge> getEdges() {
            return edges;
        }

        public void addEdge(Edge e) {
            this.edges.add(e);
        }



        private static class Edge {
            private Node source;
            private Node target;
            private Set<String> ops;

            public Edge(Node source, Node target, Set<String> ops) {
                this.source = source;
                this.target = target;
                this.ops = ops;
            }

            public Node getSource() {
                return source;
            }

            public void setSource(Node source) {
                this.source = source;
            }

            public Node getTarget() {
                return target;
            }

            public void setTarget(Node target) {
                this.target = target;
            }

            public Set<String> getOps() {
                return ops;
            }

            public void setOps(Set<String> ops) {
                this.ops = ops;
            }
        }
    }
}
