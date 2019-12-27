package gov.nist.csd.pm.operations;

import com.google.gson.Gson;

import java.util.*;

public class OperationSet extends HashSet<String> {

    public OperationSet(String ... ops) {
        this.addAll(Arrays.asList(ops));
    }

    public OperationSet(Collection<String> ops) {
        this.addAll(ops);
    }

    public OperationSet getAdminOps() {
        OperationSet adminOps = new OperationSet();
        for (String op : this) {
            if (Operations.isAdmin(op)) {
                adminOps.add(op);
            }
        }
        return adminOps;
    }

    public OperationSet getResourceOps() {
        OperationSet resourceOps = new OperationSet();
        for (String op : this) {
            if (Operations.isResource(op)) {
                resourceOps.add(op);
            }
        }
        return resourceOps;
    }
}
