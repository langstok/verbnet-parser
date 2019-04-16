package io.github.semlink.verbnet.semantics;

import io.github.semlink.verbnet.type.SemanticArgumentType;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.experimental.Accessors;

import static io.github.semlink.util.StringUtils.capitalized;

/**
 * {@link SemanticArgument} with a variable component.
 *
 * @author jgung
 */
public abstract class VariableSemanticArgument<T> extends SemanticArgument {

    @Getter
    @Setter
    @Accessors(fluent = true)
    protected T variable;

    public VariableSemanticArgument(@NonNull SemanticArgumentType type, @NonNull String value) {
        super(type, value);
    }

    @Override
    public String toString() {
        return capitalized(type) + "(" + value + (variable == null ? "" : " = " + variable.toString()) + ")";
    }
}