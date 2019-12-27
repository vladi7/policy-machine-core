package gov.nist.csd.pm.pip.graph;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import gov.nist.csd.pm.epp.events.AssignToEvent;
import gov.nist.csd.pm.exceptions.PMException;
import gov.nist.csd.pm.operations.OperationSet;
import gov.nist.csd.pm.pip.graph.model.nodes.Node;
import gov.nist.csd.pm.pip.graph.model.relationships.Assignment;
import gov.nist.csd.pm.pip.graph.model.relationships.Association;

import java.util.*;

import static gov.nist.csd.pm.pip.graph.model.nodes.NodeType.UA;

public class GraphSerializer {

    private GraphSerializer() {
    }

    /**
     * Given a Graph interface, serialize the graph to a json string.
     *
     * Here is an example of the format:
     * {
     *   "nodes": [
     *     {
     *       "id": 1,
     *       "name": "pc1",
     *       "type": "PC",
     *       "properties": {}
     *     },
     *     ...
     *   ],
     *   "assignments": [
     *     {
     *       "sourceID": 2,
     *       "targetID": 1
     *     },
     *     ...
     *   ],
     *   "associations": [
     *     {
     *       "operations": [
     *         "read",
     *         "write"
     *       ],
     *       "sourceID": 4,
     *       "targetID": 2
     *     }
     *   ]
     * }
     *
     *
     * @param graph the graph to serialize.
     * @return a json string representation of the given graph.
     * @throws PMException if there is an error accessing the graph.
     */
    public static String toJson(Graph graph) throws PMException {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();

        Collection<Node> nodes = graph.getNodes();
        HashSet<Assignment> jsonAssignments = new HashSet<>();
        HashSet<JsonAssociation> jsonAssociations = new HashSet<>();
        for (Node node : nodes) {
            Set<Long> parents = graph.getParents(node.getID());

            for (Long parent : parents) {
                jsonAssignments.add(new Assignment(node.getID(), parent));
            }

            Map<Long, Association> associations = graph.getSourceAssociations(node.getID());
            for (long targetID : associations.keySet()) {
                Association assoc = associations.get(targetID);
                jsonAssociations.add(new JsonAssociation(node.getID(), targetID, assoc.getOperations(), assoc.isRecursive()));
            }
        }

        return gson.toJson(new JsonGraph(nodes, jsonAssignments, jsonAssociations));
    }

    /**
     * Given a json string, deserialize it into the provided Graph implementation.
     *
     * @param graph the graph to deserialize the json into.
     * @param json the json string to deserialize.
     * @return the provided Graph implementation with the data from the json string.
     * @throws PMException if there is an error converting the string to a Graph.
     */
    public static Graph fromJson(Graph graph, String json) throws PMException {
        JsonGraph jsonGraph = new Gson().fromJson(json, JsonGraph.class);

        Collection<Node> nodes = jsonGraph.getNodes();
        for (Node node : nodes) {
            graph.createNode(node.getID(), node.getName(), node.getType(), node.getProperties());
        }

        Set<Assignment> assignments = jsonGraph.getAssignments();
        for (Assignment assignment : assignments) {
            graph.assign(assignment.getSourceID(), assignment.getTargetID());
        }

        Set<JsonAssociation> associations = jsonGraph.getAssociations();
        for (JsonAssociation association : associations) {
            long uaID = association.getSourceID();
            long targetID = association.getTargetID();
            graph.associate(uaID, targetID, new OperationSet(association.getOperations()), association.isRecursive());
        }

        return graph;
    }

    private static class JsonGraph {
        Collection<Node> nodes;
        Set<Assignment>  assignments;
        Set<JsonAssociation> associations;

        JsonGraph(Collection<Node> nodes, Set<Assignment> assignments, Set<JsonAssociation> associations) {
            this.nodes = nodes;
            this.assignments = assignments;
            this.associations = associations;
        }

        Collection<Node> getNodes() {
            return nodes;
        }

        Set<Assignment> getAssignments() {
            return assignments;
        }

        Set<JsonAssociation> getAssociations() {
            return associations;
        }
    }

    private static class JsonAssociation {
        private long sourceID;
        private long targetID;
        private Set<String> operations;
        private boolean recursive;

        public JsonAssociation(long sourceID, long targetID, Set<String> operations, boolean recursive) {
            this.sourceID = sourceID;
            this.targetID = targetID;
            this.operations = operations;
            this.recursive = recursive;
        }

        public long getSourceID() {
            return sourceID;
        }

        public void setSourceID(long sourceID) {
            this.sourceID = sourceID;
        }

        public long getTargetID() {
            return targetID;
        }

        public void setTargetID(long targetID) {
            this.targetID = targetID;
        }

        public Set<String> getOperations() {
            return operations;
        }

        public void setOperations(Set<String> operations) {
            this.operations = operations;
        }

        public boolean isRecursive() {
            return recursive;
        }

        public void setRecursive(boolean recursive) {
            this.recursive = recursive;
        }
    }
}
