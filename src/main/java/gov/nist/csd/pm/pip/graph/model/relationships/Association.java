package gov.nist.csd.pm.pip.graph.model.relationships;

import gov.nist.csd.pm.exceptions.PMException;
import gov.nist.csd.pm.operations.OperationSet;
import gov.nist.csd.pm.operations.Operations;
import gov.nist.csd.pm.pip.graph.model.nodes.NodeType;

import java.io.Serializable;
import java.util.*;

import static gov.nist.csd.pm.pip.graph.model.nodes.NodeType.*;

/**
 * This object represents an Association in a NGAC graph. An association is a relationship between two nodes,
 * similar to an assignment, except an Association has a set of operations included.
 */
public class Association extends Relationship implements Serializable {

    private static Map<NodeType, NodeType[]> validAssociations = new EnumMap<>(NodeType.class);
    static {
        validAssociations.put(PC, new NodeType[]{});
        validAssociations.put(OA, new NodeType[]{});
        validAssociations.put(O, new NodeType[]{});
        validAssociations.put(UA, new NodeType[]{UA, OA});
        validAssociations.put(U, new NodeType[]{});
    }

    private OperationSet operations;
    private boolean recursive;

    public Association(long uaID, long targetID, OperationSet operations, boolean recursive) {
        super(uaID, targetID);
        this.operations = operations;
        this.recursive = recursive;
    }

    public OperationSet getOperations() {
        return operations;
    }

    public void setOperations(OperationSet operations) {
        this.operations = operations;
    }

    public static Map<NodeType, NodeType[]> getValidAssociations() {
        return validAssociations;
    }

    public static void setValidAssociations(Map<NodeType, NodeType[]> validAssociations) {
        Association.validAssociations = validAssociations;
    }

    public boolean isRecursive() {
        return recursive;
    }

    public void setRecursive(boolean recursive) {
        this.recursive = recursive;
    }

    public OperationSet getAdminOps() {
        OperationSet set = this.operations;
        set.removeIf(op -> !Operations.isAdmin(op));
        return set;
    }

    public OperationSet getResourceOps() {
        OperationSet set = this.operations;
        set.removeIf(Operations::isAdmin);
        return set;
    }

    /**
     * Check if the provided types create a valid association.
     *
     * @param uaType     the type of the source node in the association. This should always be a user Attribute,
     *                   so an InvalidAssociationException will be thrown if it's not.
     * @param targetType the type of the target node. This can be either an Object Attribute or a user attribute.
     * @throws PMException if the provided types do not make a valid Association under NGAC
     */
    public static void checkAssociation(NodeType uaType, NodeType targetType) throws PMException {
        NodeType[] check = validAssociations.get(uaType);
        for (NodeType nt : check) {
            if (nt.equals(targetType)) {
                return;
            }
        }

        throw new PMException(String.format("cannot associate a node of type %s to a node of type %s", uaType, targetType));
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Association)) {
            return false;
        }

        Association association = (Association) o;
        return this.sourceID == association.sourceID &&
                this.targetID == association.targetID &&
                this.operations.equals(association.operations);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceID, targetID, operations);
    }
}
