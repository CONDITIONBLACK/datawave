package datawave.query.language.functions.jexl;

import datawave.query.language.functions.QueryFunction;

public class Include extends AbstractEvaluationPhaseFunction {
    public Include() {
        super("include");
    }
    
    @Override
    public String toString() {
        return super.toString("filter:includeRegex(", ")");
    }
    
    @Override
    public QueryFunction duplicate() {
        return new Include();
    }
}
