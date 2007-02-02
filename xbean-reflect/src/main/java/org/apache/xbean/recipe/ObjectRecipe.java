/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.xbean.recipe;

import org.apache.xbean.Classes;
import org.apache.xbean.propertyeditor.PropertyEditors;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.security.AccessController;
import java.security.PrivilegedAction;

/**
 * @version $Rev: 6688 $ $Date: 2005-12-29T02:08:29.200064Z $
 */
public class ObjectRecipe implements Recipe {
    private final String type;
    private final String factoryMethod;
    private final String[] constructorArgNames;
    private final Class[] constructorArgTypes;
    private final LinkedHashMap properties;
    private final List options = new ArrayList();
    private final Map unsetProperties = new LinkedHashMap();
    public ObjectRecipe(Class type) {
        this(type.getName());
    }

    public ObjectRecipe(Class type, String factoryMethod) {
        this(type.getName(), factoryMethod);
    }

    public ObjectRecipe(Class type, Map properties) {
        this(type.getName(), properties);
    }

    public ObjectRecipe(Class type, String[] constructorArgNames, Class[] constructorArgTypes) {
        this(type.getName(), constructorArgNames, constructorArgTypes);
    }

    public ObjectRecipe(Class type, String factoryMethod, String[] constructorArgNames, Class[] constructorArgTypes) {
        this(type.getName(), factoryMethod, constructorArgNames, constructorArgTypes);
    }

    public ObjectRecipe(String typeName) {
        this(typeName, null, null, null, null);
    }

    public ObjectRecipe(String typeName, String factoryMethod) {
        this(typeName, factoryMethod, null, null, null);
    }

    public ObjectRecipe(String typeName, Map properties) {
        this(typeName, null, null, null, properties);
    }

    public ObjectRecipe(String typeName, String[] constructorArgNames, Class[] constructorArgTypes) {
        this(typeName, null, constructorArgNames, constructorArgTypes, null);
    }

    public ObjectRecipe(String typeName, String factoryMethod, String[] constructorArgNames, Class[] constructorArgTypes) {
        this(typeName, factoryMethod, constructorArgNames, constructorArgTypes, null);
    }

    public ObjectRecipe(String type, String factoryMethod, String[] constructorArgNames, Class[] constructorArgTypes, Map properties) {
        this.type = type;
        this.factoryMethod = factoryMethod;
        if (constructorArgNames != null) {
            this.constructorArgNames = constructorArgNames;
        } else {
            this.constructorArgNames = new String[0];
        }
        if (constructorArgTypes != null) {
            this.constructorArgTypes = constructorArgTypes;
        } else {
            this.constructorArgTypes = new Class[0];
        }
        if (properties != null) {
            this.properties = new LinkedHashMap(properties);
            setAllProperties(properties);
        } else {
            this.properties = new LinkedHashMap();
        }
    }

    public void allow(Option option){
        options.add(option);
    }

    public void disallow(Option option){
        options.remove(option);
    }

    public Object getProperty(String name) {
        Object value = properties.get(new Property(name));
        return value;
    }

    public void setProperty(String name, Object value) {
        setProperty(new Property(name), value);
    }

    public void setFieldProperty(String name, Object value){
        setProperty(new FieldProperty(name), value);
        options.add(Option.FIELD_INJECTION);
    }

    public void setMethodProperty(String name, Object value){
        setProperty(new SetterProperty(name), value);
    }

    private void setProperty(Property key, Object value) {
        if (!RecipeHelper.isSimpleType(value)) {
            value = new ValueRecipe(value);
        }
        properties.put(key, value);
    }


    public void setAllProperties(Map map) {
        if (map == null) throw new NullPointerException("map is null");
        for (Iterator iterator = map.entrySet().iterator(); iterator.hasNext();) {
            Map.Entry entry = (Map.Entry) iterator.next();
            String name = (String) entry.getKey();
            Object value = entry.getValue();
            setProperty(name, value);
        }
    }

    public Object create() throws ConstructionException {
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        return create(contextClassLoader);
    }

    public Object create(ClassLoader classLoader) throws ConstructionException {
        unsetProperties.clear();
        // load the type class
        Class typeClass = null;
        try {
            typeClass = Class.forName(type, true, classLoader);
        } catch (ClassNotFoundException e) {
            throw new ConstructionException("Type class could not be found: " + type);
        }

        // verify that it is a class we can construct
        if (!Modifier.isPublic(typeClass.getModifiers())) {
            throw new ConstructionException("Class is not public: " + Classes.getClassName(typeClass, true));
        }
        if (Modifier.isInterface(typeClass.getModifiers())) {
            throw new ConstructionException("Class is an interface: " + Classes.getClassName(typeClass, true));
        }
        if (Modifier.isAbstract(typeClass.getModifiers())) {
            throw new ConstructionException("Class is abstract: " + Classes.getClassName(typeClass, true));
        }

        // get object values for all recipe properties
        Map propertyValues = new LinkedHashMap(properties);
        for (Iterator iterator = propertyValues.entrySet().iterator(); iterator.hasNext();) {
            Map.Entry entry = (Map.Entry) iterator.next();
            Object value = entry.getValue();
            if (value instanceof Recipe) {
                Recipe recipe = ((Recipe) value);
                value = recipe.create(classLoader);
                entry.setValue(value);
            }
        }

        // create the instance
        Object instance = createInstance(typeClass, propertyValues);

        boolean allowPrivate = options.contains(Option.PRIVATE_PROPERTIES);
        boolean ignoreMissingProperties = options.contains(Option.IGNORE_MISSING_PROPERTIES);

        // set remaining properties
        for (Iterator iterator = propertyValues.entrySet().iterator(); iterator.hasNext();) {
            Map.Entry entry = (Map.Entry) iterator.next();
            Property propertyName = (Property) entry.getKey();
            Object propertyValue = entry.getValue();

            Member member;
            try {
                if (propertyName instanceof SetterProperty){
                    member = new MethodMember(findSetter(typeClass, propertyName.name, propertyValue, allowPrivate));
                } else if (propertyName instanceof FieldProperty){
                    member = new FieldMember(findField(typeClass, propertyName.name, propertyValue, allowPrivate));
                } else {
                    try {
                        member = new MethodMember(findSetter(typeClass, propertyName.name, propertyValue, allowPrivate));
                    } catch (MissingAccessorException noSetter) {
                        if (!options.contains(Option.FIELD_INJECTION)) {
                            throw noSetter;
                        }

                        try {
                            member = new FieldMember(findField(typeClass, propertyName.name, propertyValue, allowPrivate));
                        } catch (MissingAccessorException noField) {
                            throw (noField.getMatchLevel() > noSetter.getMatchLevel())? noField: noSetter;
                        }
                    }
                }
            } catch (MissingAccessorException e) {
                if (ignoreMissingProperties){
                    unsetProperties.put(propertyName.name, propertyValue);
                    continue;
                } else {
                    throw e;
                }
            }

            try {
                propertyValue = convert(member.getType(), propertyValue);
                member.setValue(instance, propertyValue);
            } catch (Exception e) {
                Throwable t = e;
                if (e instanceof InvocationTargetException) {
                    InvocationTargetException invocationTargetException = (InvocationTargetException) e;
                    if (invocationTargetException.getCause() != null) {
                        t = invocationTargetException.getCause();
                    }
                }
                throw new ConstructionException("Error setting property: " + member, t);
            }
        }
        return instance;
    }

    public Map getUnsetProperties() {
        return new LinkedHashMap(unsetProperties);
    }

    private Object[] extractConstructorArgs(Map propertyValues, Class[] constructorArgTypes) {
        Object[] parameters = new Object[constructorArgNames.length];
        for (int i = 0; i < constructorArgNames.length; i++) {
            Property name = new Property(constructorArgNames[i]);
            Class type = constructorArgTypes[i];

            Object value;
            if (propertyValues.containsKey(name)) {
                value = propertyValues.remove(name);
                if (!isInstance(type, value) && !isConvertable(type, value)) {
                    throw new ConstructionException("Invalid and non-convertable constructor parameter type: " +
                            "name=" + name + ", " +
                            "index=" + i + ", " +
                            "expected=" + Classes.getClassName(type, true) + ", " +
                            "actual=" + Classes.getClassName(value, true));
                }
                value = convert(type, value);
            } else {
                value = getDefaultValue(type);
            }


            parameters[i] = value;
        }
        return parameters;
    }

    private static Object convert(Class type, Object value) {
        if (value instanceof String && (type != Object.class)) {
            String stringValue = (String) value;
            value = PropertyEditors.getValue(type, stringValue);
        }
        return value;
    }

    private static Object getDefaultValue(Class type) {
        if (type.equals(Boolean.TYPE)) {
            return Boolean.FALSE;
        } else if (type.equals(Character.TYPE)) {
            return new Character((char) 0);
        } else if (type.equals(Byte.TYPE)) {
            return new Byte((byte) 0);
        } else if (type.equals(Short.TYPE)) {
            return new Short((short) 0);
        } else if (type.equals(Integer.TYPE)) {
            return new Integer(0);
        } else if (type.equals(Long.TYPE)) {
            return new Long(0);
        } else if (type.equals(Float.TYPE)) {
            return new Float(0);
        } else if (type.equals(Double.TYPE)) {
            return new Double(0);
        }
        return null;
    }

    private Object createInstance(Class typeClass, Map propertyValues) {
        if (factoryMethod != null) {
            Method method = selectFactory(typeClass);
            // get the constructor parameters
            Object[] parameters = extractConstructorArgs(propertyValues, method.getParameterTypes());

            try {
                Object object = method.invoke(null, parameters);
                return object;
            } catch (Exception e) {
                Throwable t = e;
                if (e instanceof InvocationTargetException) {
                    InvocationTargetException invocationTargetException = (InvocationTargetException) e;
                    if (invocationTargetException.getCause() != null) {
                        t = invocationTargetException.getCause();
                    }
                }
                throw new ConstructionException("Error invoking factory method: " + method, t);
            }
        } else {
            Constructor constructor = selectConstructor(typeClass);
            // get the constructor parameters
            Object[] parameters = extractConstructorArgs(propertyValues, constructor.getParameterTypes());

            try {
                Object object = constructor.newInstance(parameters);
                return object;
            } catch (Exception e) {
                Throwable t = e;
                if (e instanceof InvocationTargetException) {
                    InvocationTargetException invocationTargetException = (InvocationTargetException) e;
                    if (invocationTargetException.getCause() != null) {
                        t = invocationTargetException.getCause();
                    }
                }
                throw new ConstructionException("Error invoking constructor: " + constructor, t);
            }
        }
    }

    private Method selectFactory(Class typeClass) {
        if (constructorArgNames.length > 0 && constructorArgTypes.length == 0) {
            ArrayList matches = new ArrayList();

            Method[] methods = typeClass.getMethods();
            for (int i = 0; i < methods.length; i++) {
                Method method = methods[i];
                if (method.getName().equals(factoryMethod) && method.getParameterTypes().length == constructorArgNames.length) {
                    try {
                        checkFactory(method);
                        matches.add(method);
                    } catch (Exception dontCare) {
                    }
                }
            }

            if (matches.size() < 1) {
                StringBuffer buffer = new StringBuffer("No parameter types supplied; unable to find a potentially valid factory method: ");
                buffer.append("public static Object ").append(factoryMethod);
                buffer.append(toArgumentList(constructorArgNames));
                throw new ConstructionException(buffer.toString());
            } else if (matches.size() > 1) {
                StringBuffer buffer = new StringBuffer("No parameter types supplied; found too many potentially valid factory methods: ");
                buffer.append("public static Object ").append(factoryMethod);
                buffer.append(toArgumentList(constructorArgNames));
                throw new ConstructionException(buffer.toString());
            }

            return (Method) matches.get(0);
        }

        try {
            Method method = typeClass.getMethod(factoryMethod, constructorArgTypes);

            checkFactory(method);

            return method;
        } catch (NoSuchMethodException e) {
            // try to find a matching private method
            Method[] methods = typeClass.getDeclaredMethods();
            for (int i = 0; i < methods.length; i++) {
                Method method = methods[i];
                if (method.getName().equals(factoryMethod) && isAssignableFrom(constructorArgTypes, method.getParameterTypes())) {
                    if (!Modifier.isPublic(method.getModifiers())) {
                        throw new ConstructionException("Factory method is not public: " + method);
                    }
                }
            }

            StringBuffer buffer = new StringBuffer("Unable to find a valid factory method: ");
            buffer.append("public static Object ").append(Classes.getClassName(typeClass, true)).append(".");
            buffer.append(factoryMethod).append(toParameterList(constructorArgTypes));
            throw new ConstructionException(buffer.toString());
        }
    }

    private void checkFactory(Method method) {
        if (!Modifier.isPublic(method.getModifiers())) {
            // this will never occur since private methods are not returned from
            // getMethod, but leave this here anyway, just to be safe
            throw new ConstructionException("Factory method is not public: " + method);
        }

        if (!Modifier.isStatic(method.getModifiers())) {
            throw new ConstructionException("Factory method is not static: " + method);
        }

        if (method.getReturnType().equals(Void.TYPE)) {
            throw new ConstructionException("Factory method does not return anything: " + method);
        }

        if (method.getReturnType().isPrimitive()) {
            throw new ConstructionException("Factory method returns a primitive type: " + method);
        }
    }

    private Constructor selectConstructor(Class typeClass) {
        if (constructorArgNames.length > 0 && constructorArgTypes.length == 0) {
            ArrayList matches = new ArrayList();

            Constructor[] constructors = typeClass.getConstructors();
            for (int i = 0; i < constructors.length; i++) {
                Constructor constructor = constructors[i];
                if (constructor.getParameterTypes().length == constructorArgNames.length) {
                    matches.add(constructor);
                }
            }

            if (matches.size() < 1) {
                StringBuffer buffer = new StringBuffer("No parameter types supplied; unable to find a potentially valid constructor: ");
                buffer.append("constructor= public ").append(Classes.getClassName(typeClass, true));
                buffer.append(toArgumentList(constructorArgNames));
                throw new ConstructionException(buffer.toString());
            } else if (matches.size() > 1) {
                StringBuffer buffer = new StringBuffer("No parameter types supplied; found too many potentially valid constructors: ");
                buffer.append("constructor= public ").append(Classes.getClassName(typeClass, true));
                buffer.append(toArgumentList(constructorArgNames));
                throw new ConstructionException(buffer.toString());
            }

            return (Constructor) matches.get(0);
        }

        try {
            Constructor constructor = typeClass.getConstructor(constructorArgTypes);

            if (!Modifier.isPublic(constructor.getModifiers())) {
                // this will never occur since private constructors are not returned from
                // getConstructor, but leave this here anyway, just to be safe
                throw new ConstructionException("Constructor is not public: " + constructor);
            }

            return constructor;
        } catch (NoSuchMethodException e) {
            // try to find a matching private method
            Constructor[] constructors = typeClass.getDeclaredConstructors();
            for (int i = 0; i < constructors.length; i++) {
                Constructor constructor = constructors[i];
                if (isAssignableFrom(constructorArgTypes, constructor.getParameterTypes())) {
                    if (!Modifier.isPublic(constructor.getModifiers())) {
                        throw new ConstructionException("Constructor is not public: " + constructor);
                    }
                }
            }

            StringBuffer buffer = new StringBuffer("Unable to find a valid constructor: ");
            buffer.append("constructor= public ").append(Classes.getClassName(typeClass, true));
            buffer.append(toParameterList(constructorArgTypes));
            throw new ConstructionException(buffer.toString());
        }
    }

    private String toParameterList(Class[] parameterTypes) {
        StringBuffer buffer = new StringBuffer();
        buffer.append("(");
        for (int i = 0; i < parameterTypes.length; i++) {
            Class type = parameterTypes[i];
            if (i > 0) buffer.append(", ");
            buffer.append(Classes.getClassName(type, true));
        }
        buffer.append(")");
        return buffer.toString();
    }

    private String toArgumentList(String[] parameterNames) {
        StringBuffer buffer = new StringBuffer();
        buffer.append("(");
        for (int i = 0; i < parameterNames.length; i++) {
            String parameterName = parameterNames[i];
            if (i > 0) buffer.append(", ");
            buffer.append('<').append(parameterName).append('>');
        }
        buffer.append(")");
        return buffer.toString();
    }

    public static Method findSetter(Class typeClass, String propertyName, Object propertyValue, boolean allowPrivate) {
        if (propertyName == null) throw new NullPointerException("name is null");
        if (propertyName.length() == 0) throw new IllegalArgumentException("name is an empty string");

        String setterName = "set" + Character.toUpperCase(propertyName.charAt(0));
        if (propertyName.length() > 0) {
            setterName += propertyName.substring(1);
        }


        int matchLevel = 0;
        MissingAccessorException missException = null;

        List methods = new ArrayList(Arrays.asList(typeClass.getMethods()));
        methods.addAll(Arrays.asList(typeClass.getDeclaredMethods()));
        for (Iterator iterator = methods.iterator(); iterator.hasNext();) {
            Method method = (Method) iterator.next();
            if (method.getName().equals(setterName)) {
                if (method.getParameterTypes().length == 0) {
                    if (matchLevel < 1) {
                        matchLevel = 1;
                        missException = new MissingAccessorException("Setter takes no parameters: " + method, matchLevel);
                    }
                    continue;
                }

                if (method.getParameterTypes().length > 1) {
                    if (matchLevel < 1) {
                        matchLevel = 1;
                        missException = new MissingAccessorException("Setter takes more then one parameter: " + method, matchLevel);
                    }
                    continue;
                }

                if (method.getReturnType() != Void.TYPE) {
                    if (matchLevel < 2) {
                        matchLevel = 2;
                        missException = new MissingAccessorException("Setter returns a value: " + method, matchLevel);
                    }
                    continue;
                }

                if (Modifier.isAbstract(method.getModifiers())) {
                    if (matchLevel < 3) {
                        matchLevel = 3;
                        missException = new MissingAccessorException("Setter is abstract: " + method, matchLevel);
                    }
                    continue;
                }

                if (!allowPrivate && !Modifier.isPublic(method.getModifiers())) {
                    if (matchLevel < 4) {
                        matchLevel = 4;
                        missException = new MissingAccessorException("Setter is not public: " + method, matchLevel);
                    }
                    continue;
                }

                if (Modifier.isStatic(method.getModifiers())) {
                    if (matchLevel < 4) {
                        matchLevel = 4;
                        missException = new MissingAccessorException("Setter is static: " + method, matchLevel);
                    }
                    continue;
                }

                Class methodParameterType = method.getParameterTypes()[0];
                if (methodParameterType.isPrimitive() && propertyValue == null) {
                    if (matchLevel < 6) {
                        matchLevel = 6;
                        missException = new MissingAccessorException("Null can not be assigned to " +
                                Classes.getClassName(methodParameterType, true) + ": " + method, matchLevel);
                    }
                    continue;
                }


                if (!isInstance(methodParameterType, propertyValue) && !isConvertable(methodParameterType, propertyValue)) {
                    if (matchLevel < 5) {
                        matchLevel = 5;
                        missException = new MissingAccessorException(Classes.getClassName(propertyValue, true) + " can not be assigned or converted to " +
                                Classes.getClassName(methodParameterType, true) + ": " + method, matchLevel);
                    }
                    continue;
                }

                if (allowPrivate && !Modifier.isPublic(method.getModifiers())) {
                    setAccessible(method);
                }

                return method;
            }

        }

        if (missException != null) {
            throw missException;
        } else {
            StringBuffer buffer = new StringBuffer("Unable to find a valid setter method: ");
            buffer.append("public void ").append(Classes.getClassName(typeClass, true)).append(".");
            buffer.append(setterName).append("(").append(Classes.getClassName(propertyValue, true)).append(")");
            throw new MissingAccessorException(buffer.toString(), -1);
        }
    }

    public static Field findField(Class typeClass, String propertyName, Object propertyValue, boolean allowPrivate) {
        if (propertyName == null) throw new NullPointerException("name is null");
        if (propertyName.length() == 0) throw new IllegalArgumentException("name is an empty string");

        int matchLevel = 0;
        MissingAccessorException missException = null;

        List fields = new ArrayList(Arrays.asList(typeClass.getDeclaredFields()));
        Class parent = typeClass.getSuperclass();
        while (parent != null){
            fields.addAll(Arrays.asList(parent.getDeclaredFields()));
            parent = parent.getSuperclass();
        }

        for (Iterator iterator = fields.iterator(); iterator.hasNext();) {
            Field field = (Field) iterator.next();
            if (field.getName().equals(propertyName)) {

                if (!allowPrivate && !Modifier.isPublic(field.getModifiers())) {
                    if (matchLevel < 4) {
                        matchLevel = 4;
                        missException = new MissingAccessorException("Field is not public: " + field, matchLevel);
                    }
                    continue;
                }

                if (Modifier.isStatic(field.getModifiers())) {
                    if (matchLevel < 4) {
                        matchLevel = 4;
                        missException = new MissingAccessorException("Field is static: " + field, matchLevel);
                    }
                    continue;
                }

                Class fieldType = field.getType();
                if (fieldType.isPrimitive() && propertyValue == null) {
                    if (matchLevel < 6) {
                        matchLevel = 6;
                        missException = new MissingAccessorException("Null can not be assigned to " +
                                Classes.getClassName(fieldType, true) + ": " + field, matchLevel);
                    }
                    continue;
                }


                if (!isInstance(fieldType, propertyValue) && !isConvertable(fieldType, propertyValue)) {
                    if (matchLevel < 5) {
                        matchLevel = 5;
                        missException = new MissingAccessorException(Classes.getClassName(propertyValue, true) + " can not be assigned or converted to " +
                                Classes.getClassName(fieldType, true) + ": " + field, matchLevel);
                    }
                    continue;
                }

                if (allowPrivate && !Modifier.isPublic(field.getModifiers())) {
                    setAccessible(field);
                }

                return field;
            }

        }

        if (missException != null) {
            throw missException;
        } else {
            StringBuffer buffer = new StringBuffer("Unable to find a valid field: ");
            buffer.append("public ").append(" ").append(Classes.getClassName(propertyValue, true));
            buffer.append(" ").append(propertyName).append(";");
            throw new MissingAccessorException(buffer.toString(), -1);
        }
    }

    public static boolean isConvertable(Class methodParameterType, Object propertyValue) {
        return (propertyValue instanceof String && PropertyEditors.canConvert(methodParameterType));
    }

    public static boolean isInstance(Class type, Object instance) {
        if (type.isPrimitive()) {
            // for primitives the insance can't be null
            if (instance == null) {
                return false;
            }

            // verify instance is the correct wrapper type
            if (type.equals(boolean.class)) {
                return instance instanceof Boolean;
            } else if (type.equals(char.class)) {
                return instance instanceof Character;
            } else if (type.equals(byte.class)) {
                return instance instanceof Byte;
            } else if (type.equals(short.class)) {
                return instance instanceof Short;
            } else if (type.equals(int.class)) {
                return instance instanceof Integer;
            } else if (type.equals(long.class)) {
                return instance instanceof Long;
            } else if (type.equals(float.class)) {
                return instance instanceof Float;
            } else if (type.equals(double.class)) {
                return instance instanceof Double;
            } else {
                throw new AssertionError("Invalid primitve type: " + type);
            }
        }

        return instance == null || type.isInstance(instance);
    }

    public static boolean isAssignableFrom(Class expected, Class actual) {
        if (expected.isPrimitive()) {
            // verify actual is the correct wrapper type
            if (expected.equals(boolean.class)) {
                return actual.equals(Boolean.class);
            } else if (expected.equals(char.class)) {
                return actual.equals(Character.class);
            } else if (expected.equals(byte.class)) {
                return actual.equals(Byte.class);
            } else if (expected.equals(short.class)) {
                return actual.equals(Short.class);
            } else if (expected.equals(int.class)) {
                return actual.equals(Integer.class);
            } else if (expected.equals(long.class)) {
                return actual.equals(Long.class);
            } else if (expected.equals(float.class)) {
                return actual.equals(Float.class);
            } else if (expected.equals(double.class)) {
                return actual.equals(Double.class);
            } else {
                throw new AssertionError("Invalid primitve type: " + expected);
            }
        }

        return expected.isAssignableFrom(actual);
    }

    public static boolean isAssignableFrom(Class[] expectedTypes, Class[] actualTypes) {
        if (expectedTypes.length != actualTypes.length) {
            return false;
        }
        for (int i = 0; i < expectedTypes.length; i++) {
            Class expectedType = expectedTypes[i];
            Class actualType = actualTypes[i];
            if (!isAssignableFrom(expectedType, actualType)) {
                return false;
            }
        }
        return true;
    }

    private static void setAccessible(final Method method) {
        AccessController.doPrivileged(new PrivilegedAction() {
            public Object run() {
                method.setAccessible(true);
                return null;
            }
        });
    }

    private static void setAccessible(final Field field) {
        AccessController.doPrivileged(new PrivilegedAction() {
            public Object run() {
                field.setAccessible(true);
                return null;
            }
        });
    }

    public static interface Member {
        Class getType();
        void setValue(Object instance, Object value) throws Exception;
    }

    public static class MethodMember implements Member {
        private final Method setter;

        public MethodMember(Method method) {
            this.setter = method;
        }

        public Class getType() {
            return setter.getParameterTypes()[0];
        }

        public void setValue(Object instance, Object value) throws Exception {
            setter.invoke(instance, new Object[]{value});
        }

        public String toString() {
            return setter.toString();
        }
    }

    public static class FieldMember implements Member {
        private final Field field;

        public FieldMember(Field field) {
            this.field = field;
        }

        public Class getType() {
            return field.getType();
        }

        public void setValue(Object instance, Object value) throws Exception {
            field.set(instance, value);
        }

        public String toString() {
            return field.toString();
        }
    }

    private static class Property {
        private final String name;

        public Property(String name) {
            if (name == null) throw new NullPointerException("name is null");
            this.name = name;
        }

        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null) return false;
            if (o instanceof String){
                return this.name.equals(o);
            }
            if (o instanceof Property) {
                Property property = (Property) o;
                return this.name.equals(property.name);
            }
            return false;
        }

        public int hashCode() {
            return name.hashCode();
        }

        public String toString() {
            return name;
        }
    }

    private static class SetterProperty extends Property {
        public SetterProperty(String name) {
            super(name);
        }
        public int hashCode() {
            return super.hashCode()+2;
        }
        public String toString() {
            return "[setter] "+toString();
        }

    }
    private static class FieldProperty extends Property {
        public FieldProperty(String name) {
            super(name);
        }

        public int hashCode() {
            return super.hashCode()+1;
        }
        public String toString() {
            return "[field] "+toString();
        }
    }
}
