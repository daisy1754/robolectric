package org.robolectric.bytecode;

import org.robolectric.internal.Implements;
import org.robolectric.internal.RealObject;
import org.robolectric.util.Function;
import org.robolectric.util.Join;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.fest.reflect.core.Reflection.method;

public class ShadowWrangler implements ClassHandler {
    public static final Function<Object, Object> DO_NOTHING_HANDLER = new Function<Object, Object>() {
        @Override
        public Object call(Object value) {
            return null;
        }
    };
    private static final boolean STRIP_SHADOW_STACK_TRACES = true;
    public static final Plan DO_NOTHING_PLAN = new Plan() {
        @Override public Object run(Object instance, Object[] params) throws Exception {
            return null;
        }
    };

    public boolean debug = true;

    private final ShadowMap shadowMap;
    private final Map<Class, MetaShadow> metaShadowMap = new HashMap<Class, MetaShadow>();
    private final Map<String, Plan> planCache = new LinkedHashMap<String, Plan>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Plan> eldest) {
            return size() > 500;
        }
    };

    public ShadowWrangler(ShadowMap shadowMap) {
        this.shadowMap = shadowMap;
    }

    @Override
    public void classInitializing(Class clazz) {
        Class<?> shadowClass = findDirectShadowClass(clazz);
        if (shadowClass != null) {
            try {
                Method method = shadowClass.getMethod(InstrumentingClassLoader.STATIC_INITIALIZER_METHOD_NAME);
                if (!Modifier.isStatic(method.getModifiers())) {
                    throw new RuntimeException(shadowClass.getName() + "." + method.getName() + " is not static");
                }
                method.setAccessible(true);
                method.invoke(null);
            } catch (NoSuchMethodException e) {
                RobolectricInternals.performStaticInitialization(clazz);
            } catch (InvocationTargetException e) {
                throw new RuntimeException(e);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        } else {
            RobolectricInternals.performStaticInitialization(clazz);
        }
    }

    @Override public Object initializing(Object instance) {
        return createShadowFor(instance);
    }

    @Override
    synchronized public Plan methodInvoked(String signature, boolean isStatic, Class<?> theClass) {
        if (planCache.containsKey(signature)) return planCache.get(signature);
        Plan plan = calculatePlan(signature, isStatic, theClass);
        planCache.put(signature, plan);
        return plan;
    }

    private Plan calculatePlan(String signature, boolean isStatic, Class<?> theClass) {
        final InvocationProfile invocationProfile = new InvocationProfile(signature, isStatic, theClass.getClassLoader());
        ShadowConfig shadowConfig = shadowMap.get(invocationProfile.clazz);

        // enable call-through for for inner classes if an outer class has call-through turned on
        Class<?> clazz = invocationProfile.clazz;
        while (shadowConfig == null && clazz.getDeclaringClass() != null) {
            clazz = clazz.getDeclaringClass();
            ShadowConfig outerConfig = shadowMap.get(clazz);
            if (outerConfig != null && outerConfig.callThroughByDefault) {
                shadowConfig = new ShadowConfig(Object.class.getName(), true);
            }
        }

        if (shadowConfig == null) {
            if (debug) {
                System.out.println("[DEBUG] no shadow found for " + signature + "; " + describeIfStrict(invocationProfile));
            }
            return strict(invocationProfile) ? null : DO_NOTHING_PLAN;
        } else {
            try {
                ClassLoader classLoader = theClass.getClassLoader();
                Class<?> shadowClass = classLoader.loadClass(shadowConfig.shadowClassName);
                final Method shadowMethod = shadowClass.getMethod(invocationProfile.methodName, invocationProfile.getParamClasses(classLoader));
                Class<?> declaredShadowedClass = getShadowedClass(shadowMethod);

                if (declaredShadowedClass.equals(Object.class)) {
                    // e.g. for equals(), hashCode(), toString()
                    return null;
                }

                if (strict(invocationProfile) && !declaredShadowedClass.equals(invocationProfile.clazz)) {
                    if (debug && !invocationProfile.methodName.equals(InstrumentingClassLoader.CONSTRUCTOR_METHOD_NAME)) {
                        System.out.println("[DEBUG] Method " + shadowMethod + " is meant to shadow " + declaredShadowedClass + ", not " + invocationProfile.clazz);
                    }
                    return strict(invocationProfile) ? null : DO_NOTHING_PLAN;
                }
                if (debug) {
                    System.out.println("[DEBUG] found shadow for " + signature + "; will call " + shadowMethod);
                }
                return new ShadowMethodPlan(shadowMethod);
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            } catch (NoSuchMethodException e) {
                if (debug) {
                    System.out.println("[DEBUG] no shadow for " + signature + " found on " + shadowConfig.shadowClassName + "; " + describeIfStrict(invocationProfile));
                }
                return shadowConfig.callThroughByDefault ? null : strict(invocationProfile) ? null : DO_NOTHING_PLAN;
            }
        }
    }

    private String describeIfStrict(InvocationProfile invocationProfile) {
        return (strict(invocationProfile) ? "will call real code" : "will do no-op");
    }

    private boolean strict(InvocationProfile invocationProfile) {
        return true;
//        return invocationProfile.clazz.getName().startsWith("android.support") || invocationProfile.isSpecial();
    }

    private Class<?> getShadowedClass(Method shadowMethod) {
        Class<?> shadowingClass = shadowMethod.getDeclaringClass();
        if (shadowingClass.equals(Object.class)) {
            return Object.class;
        }

        Implements implementsAnnotation = shadowingClass.getAnnotation(Implements.class);
        if (implementsAnnotation == null) {
            throw new RuntimeException(shadowingClass + " has no @" + Implements.class.getSimpleName() + " annotation");
        }
        String shadowedClassName = implementsAnnotation.className();
        if (shadowedClassName.isEmpty()) {
            return implementsAnnotation.value();
        } else {
            try {
                return shadowingClass.getClassLoader().loadClass(shadowedClassName);
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public Object intercept(String signature, Object instance, Object[] paramTypes, Class theClass) throws Throwable {
        final InvocationProfile invocationProfile = new InvocationProfile(signature, instance == null, theClass.getClassLoader());

        if (debug)
            System.out.println("DEBUG: intercepted call to " + signature + "." + invocationProfile.methodName + "(" + Join.join(", ", invocationProfile.paramTypes) + ")");

        return getInterceptionHandler(invocationProfile).call(instance);
    }

    public Function<Object, Object> getInterceptionHandler(InvocationProfile invocationProfile) {
        if (invocationProfile.clazz.equals(LinkedHashMap.class) && invocationProfile.methodName.equals("eldest")) {
            return new Function<Object, Object>() {
                @Override
                public Object call(Object value) {
                    LinkedHashMap map = (LinkedHashMap) value;
                    return map.entrySet().iterator().next();
                }
            };
        }

        return ShadowWrangler.DO_NOTHING_HANDLER;
    }

    @Override
    public <T extends Throwable> T stripStackTrace(T throwable) {
        if (STRIP_SHADOW_STACK_TRACES) {
            List<StackTraceElement> stackTrace = new ArrayList<StackTraceElement>();

            String previousClassName = null;
            String previousMethodName = null;
            String previousFileName = null;

            for (StackTraceElement stackTraceElement : throwable.getStackTrace()) {
                String methodName = stackTraceElement.getMethodName();
                String className = stackTraceElement.getClassName();
                String fileName = stackTraceElement.getFileName();

                if (methodName.equals(previousMethodName)
                        && className.equals(previousClassName)
                        && fileName != null && fileName.equals(previousFileName)
                        && stackTraceElement.getLineNumber() < 0) {
                    continue;
                }

                if (className.equals(ShadowMethodPlan.class.getName())) {
                    continue;
                }

                if (methodName.startsWith(RobolectricInternals.ROBO_PREFIX)) {
                    String fullPrefix = RobolectricInternals.directMethodName(stackTraceElement.getClassName(), "");
                    if (methodName.startsWith(fullPrefix)) {
                        methodName = methodName.substring(fullPrefix.length());
                        stackTraceElement = new StackTraceElement(className, methodName,
                                stackTraceElement.getFileName(), stackTraceElement.getLineNumber());
                    }
                }

                if (className.startsWith("sun.reflect.") || className.startsWith("java.lang.reflect.")) {
                    continue;
                }

                stackTrace.add(stackTraceElement);

                previousClassName = className;
                previousMethodName = methodName;
                previousFileName = fileName;
            }
            throwable.setStackTrace(stackTrace.toArray(new StackTraceElement[stackTrace.size()]));
        }
        return throwable;
    }

    public static Class<?> loadClass(String paramType, ClassLoader classLoader) {
        Class primitiveClass = RoboType.findPrimitiveClass(paramType);
        if (primitiveClass != null) return primitiveClass;

        int arrayLevel = 0;
        while (paramType.endsWith("[]")) {
            arrayLevel++;
            paramType = paramType.substring(0, paramType.length() - 2);
        }

        Class<?> clazz = RoboType.findPrimitiveClass(paramType);
        if (clazz == null) {
            try {
                clazz = classLoader.loadClass(paramType);
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
        }

        while (arrayLevel-- > 0) {
            clazz = Array.newInstance(clazz, 0).getClass();
        }

        return clazz;
    }

    public Object createShadowFor(Object instance) {
        Object shadow;

        String shadowClassName = shadowMap.getShadowClassName(instance.getClass());

        if (shadowClassName == null) return new Object();

        if (debug)
            System.out.println("creating new " + shadowClassName + " as shadow for " + instance.getClass().getName());
        try {
            Class<?> shadowClass = loadClass(shadowClassName, instance.getClass().getClassLoader());
            Constructor<?> constructor = findConstructor(instance, shadowClass);
            if (constructor != null) {
                shadow = constructor.newInstance(instance);
            } else {
                shadow = shadowClass.newInstance();
            }

            injectRealObjectOn(shadow, shadowClass, instance);

            return shadow;
        } catch (InstantiationException e) {
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    private void injectRealObjectOn(Object shadow, Class<?> shadowClass, Object instance) {
        MetaShadow metaShadow = getMetaShadow(shadowClass);
        for (Field realObjectField : metaShadow.realObjectFields) {
            writeField(shadow, instance, realObjectField);
        }
    }

    private MetaShadow getMetaShadow(Class<?> shadowClass) {
        synchronized (metaShadowMap) {
            MetaShadow metaShadow = metaShadowMap.get(shadowClass);
            if (metaShadow == null) {
                metaShadow = new MetaShadow(shadowClass);
                metaShadowMap.put(shadowClass, metaShadow);
            }
            return metaShadow;
        }
    }

    private Class<?> findDirectShadowClass(Class<?> originalClass) {
        ShadowConfig shadowConfig = shadowMap.get(originalClass.getName());
        if (shadowConfig == null) {
            return null;
        }
        return loadClass(shadowConfig.shadowClassName, originalClass.getClassLoader());
    }

    private Constructor<?> findConstructor(Object instance, Class<?> shadowClass) {
        Class clazz = instance.getClass();

        Constructor constructor;
        for (constructor = null; constructor == null && clazz != null; clazz = clazz.getSuperclass()) {
            try {
                constructor = shadowClass.getConstructor(clazz);
            } catch (NoSuchMethodException e) {
                // expected
            }
        }
        return constructor;
    }

    public static Object shadowOf(Object instance) {
        if (instance == null) {
            throw new NullPointerException("can't get a shadow for null");
        }
        return method(AsmInstrumentingClassLoader.GET_ROBO_DATA_METHOD_NAME).withReturnType(Object.class).in(instance).invoke();
//
//        Field field = RobolectricInternals.getShadowField(instance);
//        Object shadow = readField(instance, field);
//        if (shadow == null) {
//            shadow = shadowFor(instance);
//        }
//        return shadow;
    }

    private void writeField(Object target, Object value, Field realObjectField) {
        try {
            realObjectField.set(target, value);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private class MetaShadow {
        List<Field> realObjectFields = new ArrayList<Field>();

        public MetaShadow(Class<?> shadowClass) {
            while (shadowClass != null) {
                for (Field field : shadowClass.getDeclaredFields()) {
                    if (field.isAnnotationPresent(RealObject.class)) {
                        field.setAccessible(true);
                        realObjectFields.add(field);
                    }
                }
                shadowClass = shadowClass.getSuperclass();
            }

        }
    }

    private static class ShadowMethodPlan implements Plan {
        private final Method shadowMethod;

        public ShadowMethodPlan(Method shadowMethod) {
            this.shadowMethod = shadowMethod;
        }

        @Override public Object run(Object instance, Object[] params) throws Throwable {
            Object shadow = instance == null ? null : shadowOf(instance);
            try {
//                System.out.println("invoke " + shadowMethod);
                return shadowMethod.invoke(shadow, params);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("attempted to invoke " + shadowMethod
                        + (shadow == null ? "" : " on instance of " + shadow.getClass() + ", but " + shadow.getClass().getSimpleName() + " doesn't extend " + shadowMethod.getDeclaringClass().getSimpleName()));
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }
}
