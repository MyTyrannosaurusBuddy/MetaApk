package java.lang.invoke;

/**
 * Compile-time symbol for ECJ. Android's SDK jar omits this bootstrap class;
 * D8 desugars the emitted invokedynamic instructions before ART loads code.
 * This class is packaged only inside host-interfaces.jar, never in host DEX.
 */
public final class LambdaMetafactory {
    private LambdaMetafactory() { }

    public static CallSite metafactory(MethodHandles.Lookup caller, String name,
            MethodType invokedType, MethodType samMethodType, MethodHandle implementation,
            MethodType instantiatedMethodType) throws LambdaConversionException {
        throw new UnsupportedOperationException("Compile-time symbol only");
    }

    public static CallSite altMetafactory(MethodHandles.Lookup caller, String name,
            MethodType invokedType, Object... arguments) throws LambdaConversionException {
        throw new UnsupportedOperationException("Compile-time symbol only");
    }
}
