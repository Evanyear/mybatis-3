/**
 *    Copyright 2009-2020 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.reflection;

import org.apache.ibatis.reflection.invoker.*;
import org.apache.ibatis.reflection.property.PropertyNamer;

import java.lang.reflect.*;
import java.text.MessageFormat;
import java.util.*;
import java.util.Map.Entry;

/**
 * 反射器
 * 每个 Reflector 对应一个类。Reflector 会缓存反射操作需要的类的信息
 * This class represents a cached set of class definition information that
 * allows for easy mapping between property names and getter/setter methods.
 *
 * @author Clinton Begin
 */
public class Reflector {

  /**
   * 对应的类
   */
  private final Class<?> type;

  /**
   * 可读属性名称数组
   */
  private final String[] readablePropertyNames;

  /**
   * 可写属性名称数组
   */
  private final String[] writablePropertyNames;

  /**
   * 属性对用的的set方法  key为属性名称  value为 invoke 对象
   */
  private final Map<String, Invoker> setMethods = new HashMap<>();

  /**
   * 属性对用的的get方法  key为属性名称  value为 invoke 对象
   */
  private final Map<String, Invoker> getMethods = new HashMap<>();

  /**
   * 属性set方法的参数类型映射  key-属性名称  value-方法参数类型
   */
  private final Map<String, Class<?>> setTypes = new HashMap<>();

  /**
   * 属性get方法的参数类型映射  key-属性名称  value-返回类型
   */
  private final Map<String, Class<?>> getTypes = new HashMap<>();

  /**
   * 构造器
   */
  private Constructor<?> defaultConstructor;

  /**
   * 不区分大小写的属性集合
   */
  private Map<String, String> caseInsensitivePropertyMap = new HashMap<>();

  /**
   * 构造方法
   * @param clazz
   */
  public Reflector(Class<?> clazz) {
    // 设置对应的类
    type = clazz;
    // 初始化添加默认构造方法  默认无参构造方法
    addDefaultConstructor(clazz);
    // 初始化属性的get方法 和 get方法的返回类型
    addGetMethods(clazz);
    // 初始化属性的set方法 和 方法参数的类型
    addSetMethods(clazz);
    // 初始化 getMethods + getTypes 和 setMethods + setTypes
    addFields(clazz);
    // 初始化 readablePropertyNames,writablePropertyNames,caseInsensitivePropertyMap 属性
    readablePropertyNames = getMethods.keySet().toArray(new String[0]);
    writablePropertyNames = setMethods.keySet().toArray(new String[0]);
    for (String propName : readablePropertyNames) {
      caseInsensitivePropertyMap.put(propName.toUpperCase(Locale.ENGLISH), propName);
    }
    for (String propName : writablePropertyNames) {
      caseInsensitivePropertyMap.put(propName.toUpperCase(Locale.ENGLISH), propName);
    }
  }

  private void addDefaultConstructor(Class<?> clazz) {
    // 获取所有已声明的构造方法
    Constructor<?>[] constructors = clazz.getDeclaredConstructors();

    // 遍历构造器  查找无参构造方法 如果存在赋值
    Arrays.stream(constructors).filter(constructor -> constructor.getParameterTypes().length == 0)
      .findAny().ifPresent(constructor -> this.defaultConstructor = constructor);
  }

  /**
   * @description 初始化属性的get方法和返回值类型
   * @param
   * @author LGL
   *
   */
  private void addGetMethods(Class<?> clazz) {
    Map<String, List<Method>> conflictingGetters = new HashMap<>();
    // 获取所有方法
    Method[] methods = getClassMethods(clazz);

    // 遍历所有方法  添加冲突的get方法
    Arrays.stream(methods).filter(m -> m.getParameterTypes().length == 0 && PropertyNamer.isGetter(m.getName()))
      .forEach(m -> addMethodConflict(conflictingGetters, PropertyNamer.methodToProperty(m.getName()), m));

    // 解决 get 冲突方法
    resolveGetterConflicts(conflictingGetters);
  }

  /**
   * @description 解决 get 冲突方法
   * @param
   * @author LGL
   *
   */
  private void resolveGetterConflicts(Map<String, List<Method>> conflictingGetters) {
    //遍历所有的get冲突方法
    for (Entry<String, List<Method>> entry : conflictingGetters.entrySet()) {
      Method winner = null;

      // 获取属性名称
      String propName = entry.getKey();
      // 是否模糊判断
      boolean isAmbiguous = false;
      for (Method candidate : entry.getValue()) {
        if (winner == null) { // 为空  说明不匹配
          winner = candidate;
          continue;
        }
        Class<?> winnerType = winner.getReturnType();
        Class<?> candidateType = candidate.getReturnType();
        // 返回类型比较
        if (candidateType.equals(winnerType)) {
          if (!boolean.class.equals(candidateType)) {
            isAmbiguous = true;
            break;
          } else if (candidate.getName().startsWith("is")) {
            winner = candidate;
          }
        } else if (candidateType.isAssignableFrom(winnerType)) { // 不符合  选择子类
          // OK getter type is descendant
        } else if (winnerType.isAssignableFrom(candidateType)) {
          //符合选择子类。因为子类可以修改放大返回值。例如，父类的一个方法的返回值为 List ，子类对该方法的返回值可以覆写为 ArrayList 。
          winner = candidate;
        } else {
          isAmbiguous = true;
          break;
        }
      }

      // 添加到 getMethod和getTypes中
      addGetMethod(propName, winner, isAmbiguous);
    }
  }

  private void addGetMethod(String name, Method method, boolean isAmbiguous) {

    MethodInvoker invoker = isAmbiguous
        ? new AmbiguousMethodInvoker(method, MessageFormat.format(
            "Illegal overloaded getter method with ambiguous type for property ''{0}'' in class ''{1}''. This breaks the JavaBeans specification and can cause unpredictable results.",
            name, method.getDeclaringClass().getName()))
        : new MethodInvoker(method);
    // 添加到getMethods中
    getMethods.put(name, invoker);

    // 添加放到getType中
    Type returnType = TypeParameterResolver.resolveReturnType(method, type);
    getTypes.put(name, typeToClass(returnType));
  }

  private void addSetMethods(Class<?> clazz) {
    Map<String, List<Method>> conflictingSetters = new HashMap<>();

    // 获取所有的方法
    Method[] methods = getClassMethods(clazz);

    // 遍历所有的方法 病添加到set冲突中
    Arrays.stream(methods).filter(m -> m.getParameterTypes().length == 1 && PropertyNamer.isSetter(m.getName()))
      .forEach(m -> addMethodConflict(conflictingSetters, PropertyNamer.methodToProperty(m.getName()), m));
    // 解决set冲突
    resolveSetterConflicts(conflictingSetters);
  }

  private void addMethodConflict(Map<String, List<Method>> conflictingMethods, String name, Method method) {
    // 验证属性名是否有效
    if (isValidPropertyName(name)) {

      // 方法名相同的 冲突方法
      List<Method> list = conflictingMethods.computeIfAbsent(name, k -> new ArrayList<>());
      list.add(method);
    }
  }

  /**
   * @description 解决 set 冲突方法
   * @param
   * @author LGL
   *
   */
  private void resolveSetterConflicts(Map<String, List<Method>> conflictingSetters) {
    // 遍历 解决setter冲突
    for (Entry<String, List<Method>> entry : conflictingSetters.entrySet()) {
      // 属性名称
      String propName = entry.getKey();

      //相同的set方法名
      List<Method> setters = entry.getValue();

      // 改属性 get 方法的返回值类型
      Class<?> getterType = getTypes.get(propName);

      // 验证是get方法是否模糊
      boolean isGetterAmbiguous = getMethods.get(propName) instanceof AmbiguousMethodInvoker;
      // set方法模糊
      boolean isSetterAmbiguous = false;
      Method match = null;
      // 遍历所有的 set 方法
      for (Method setter : setters) {

        // 和 getterType 形同，直接使用
        if (!isGetterAmbiguous && setter.getParameterTypes()[0].equals(getterType)) {
          // should be the best match
          match = setter;
          break;
        }

        // 选择一个更加匹配的set方法
        if (!isSetterAmbiguous) {
          match = pickBetterSetter(match, setter, propName);
          isSetterAmbiguous = match == null;
        }
      }

      // 添加set方法 和 方法参数类型
      if (match != null) {
        addSetMethod(propName, match);
      }
    }
  }

  /**
   * @description 获取最适合的set方法
   * @param
   * @author LGL
   *
   */
  private Method pickBetterSetter(Method setter1, Method setter2, String property) {
    if (setter1 == null) {
      return setter2;
    }

    // 获取 setter1 的参数类型
    Class<?> paramType1 = setter1.getParameterTypes()[0];

    // 获取 setter2 的参数类型
    Class<?> paramType2 = setter2.getParameterTypes()[0];

    // a对象所对应类信息是b对象所对应的类信息的父类或者是父接口，简单理解即a是b的父类或接口
    // a对象所对应类信息与b对象所对应的类信息相同，简单理解即a和b为同一个类或同一个接口
    if (paramType1.isAssignableFrom(paramType2)) {
      return setter2;
    } else if (paramType2.isAssignableFrom(paramType1)) {
      return setter1;
    }

    // 如果以上两种条件均不符合
    MethodInvoker invoker = new AmbiguousMethodInvoker(setter1,
        MessageFormat.format(
            "Ambiguous setters defined for property ''{0}'' in class ''{1}'' with types ''{2}'' and ''{3}''.",
            property, setter2.getDeclaringClass().getName(), paramType1.getName(), paramType2.getName()));
    setMethods.put(property, invoker);
    Type[] paramTypes = TypeParameterResolver.resolveParamTypes(setter1, type);
    setTypes.put(property, typeToClass(paramTypes[0]));
    return null;
  }

  private void addSetMethod(String name, Method method) {
    MethodInvoker invoker = new MethodInvoker(method);
    // 添加到 setMethods 中
    setMethods.put(name, invoker);

    // 添加到 setTypes 中
    Type[] paramTypes = TypeParameterResolver.resolveParamTypes(method, type);
    setTypes.put(name, typeToClass(paramTypes[0]));
  }

  private Class<?> typeToClass(Type src) {
    Class<?> result = null;
    if (src instanceof Class) {
      result = (Class<?>) src;
    } else if (src instanceof ParameterizedType) {
      result = (Class<?>) ((ParameterizedType) src).getRawType();
    } else if (src instanceof GenericArrayType) {
      Type componentType = ((GenericArrayType) src).getGenericComponentType();
      if (componentType instanceof Class) {
        result = Array.newInstance((Class<?>) componentType, 0).getClass();
      } else {
        Class<?> componentClass = typeToClass(componentType);
        result = Array.newInstance(componentClass, 0).getClass();
      }
    }
    if (result == null) {
      result = Object.class;
    }
    return result;
  }

  /**
   * @description 初始化 getMethods + getTypes 和 setMethods + setTypes ，通过遍历 fields 属性
   * @param
   * @author LGL
   *
   */
  private void addFields(Class<?> clazz) {

    // 获取所有的属性
    Field[] fields = clazz.getDeclaredFields();
    for (Field field : fields) {
      // 添加到 setMethods 和 setTypes 中
      if (!setMethods.containsKey(field.getName())) {
        // issue #379 - removed the check for final because JDK 1.5 allows
        // modification of final fields through reflection (JSR-133). (JGB)
        // pr #16 - final static can only be set by the classloader
        int modifiers = field.getModifiers();
        if (!(Modifier.isFinal(modifiers) && Modifier.isStatic(modifiers))) {
          addSetField(field);
        }
      }

      // 添加到 getMethods 和 getTypes 中
      if (!getMethods.containsKey(field.getName())) {
        addGetField(field);
      }
    }

    // 递归，处理父类
    if (clazz.getSuperclass() != null) {
      addFields(clazz.getSuperclass());
    }
  }

  private void addSetField(Field field) {
    if (isValidPropertyName(field.getName())) {
      setMethods.put(field.getName(), new SetFieldInvoker(field));
      Type fieldType = TypeParameterResolver.resolveFieldType(field, type);
      setTypes.put(field.getName(), typeToClass(fieldType));
    }
  }

  private void addGetField(Field field) {
    if (isValidPropertyName(field.getName())) {
      getMethods.put(field.getName(), new GetFieldInvoker(field));
      Type fieldType = TypeParameterResolver.resolveFieldType(field, type);
      getTypes.put(field.getName(), typeToClass(fieldType));
    }
  }

  /**
   * @description 属性  有效性判断
   * @param
   * @author LGL
   *
   */
  private boolean isValidPropertyName(String name) {
    return !(name.startsWith("$") || "serialVersionUID".equals(name) || "class".equals(name));
  }

  /**
   * This method returns an array containing all methods
   * declared in this class and any superclass.
   * We use this method, instead of the simpler <code>Class.getMethods()</code>,
   * because we want to look for private methods as well.
   *
   * @param clazz The class
   * @return An array containing all methods in this class
   */
  private Method[] getClassMethods(Class<?> clazz) {
    // 返回结果  每个方法签名与该方法的映射 key-属性  value-返回值类型
    Map<String, Method> uniqueMethods = new HashMap<>();
    // 获取当前类
    Class<?> currentClass = clazz;
    while (currentClass != null && currentClass != Object.class) {
      // 记录当前定义的方法
      addUniqueMethods(uniqueMethods, currentClass.getDeclaredMethods());

      // we also need to look for interface methods -
      // because the class may be abstract
      // 记录当前接口中定义的方法
      Class<?>[] interfaces = currentClass.getInterfaces();
      for (Class<?> anInterface : interfaces) {
        addUniqueMethods(uniqueMethods, anInterface.getMethods());
      }

      // 获取父类
      currentClass = currentClass.getSuperclass();
    }

    // 转换成 method 数组返回
    Collection<Method> methods = uniqueMethods.values();

    return methods.toArray(new Method[0]);
  }

  private void addUniqueMethods(Map<String, Method> uniqueMethods, Method[] methods) {
    for (Method currentMethod : methods) {
      if (!currentMethod.isBridge()) { // 忽略 bridge 方法
        // 获得方法签名
        String signature = getSignature(currentMethod);
        // check to see if the method is already known
        // if it is known, then an extended class must have
        // overridden a method
        // 当 uniqueMethods 不存在是 进行添加
        if (!uniqueMethods.containsKey(signature)) {
          uniqueMethods.put(signature, currentMethod);
        }
      }
    }
  }

  private String getSignature(Method method) {
    StringBuilder sb = new StringBuilder();
    //返回类型
    Class<?> returnType = method.getReturnType();
    if (returnType != null) {
      sb.append(returnType.getName()).append('#');
    }
    // 方法名
    sb.append(method.getName());

    // 方法参数类型
    Class<?>[] parameters = method.getParameterTypes();
    for (int i = 0; i < parameters.length; i++) {
      sb.append(i == 0 ? ':' : ',').append(parameters[i].getName());
    }
    return sb.toString();
  }

  /**
   * Checks whether can control member accessible.
   *
   * @return If can control member accessible, it return {@literal true}
   * @since 3.5.0
   */
  public static boolean canControlMemberAccessible() {
    try {
      SecurityManager securityManager = System.getSecurityManager();
      if (null != securityManager) {
        securityManager.checkPermission(new ReflectPermission("suppressAccessChecks"));
      }
    } catch (SecurityException e) {
      return false;
    }
    return true;
  }

  /**
   * Gets the name of the class the instance provides information for.
   *
   * @return The class name
   */
  public Class<?> getType() {
    return type;
  }

  public Constructor<?> getDefaultConstructor() {
    if (defaultConstructor != null) {
      return defaultConstructor;
    } else {
      throw new ReflectionException("There is no default constructor for " + type);
    }
  }

  public boolean hasDefaultConstructor() {
    return defaultConstructor != null;
  }

  public Invoker getSetInvoker(String propertyName) {
    Invoker method = setMethods.get(propertyName);
    if (method == null) {
      throw new ReflectionException("There is no setter for property named '" + propertyName + "' in '" + type + "'");
    }
    return method;
  }

  public Invoker getGetInvoker(String propertyName) {
    Invoker method = getMethods.get(propertyName);
    if (method == null) {
      throw new ReflectionException("There is no getter for property named '" + propertyName + "' in '" + type + "'");
    }
    return method;
  }

  /**
   * Gets the type for a property setter.
   *
   * @param propertyName - the name of the property
   * @return The Class of the property setter
   */
  public Class<?> getSetterType(String propertyName) {
    Class<?> clazz = setTypes.get(propertyName);
    if (clazz == null) {
      throw new ReflectionException("There is no setter for property named '" + propertyName + "' in '" + type + "'");
    }
    return clazz;
  }

  /**
   * Gets the type for a property getter.
   *
   * @param propertyName - the name of the property
   * @return The Class of the property getter
   */
  public Class<?> getGetterType(String propertyName) {
    Class<?> clazz = getTypes.get(propertyName);
    if (clazz == null) {
      throw new ReflectionException("There is no getter for property named '" + propertyName + "' in '" + type + "'");
    }
    return clazz;
  }

  /**
   * Gets an array of the readable properties for an object.
   *
   * @return The array
   */
  public String[] getGetablePropertyNames() {
    return readablePropertyNames;
  }

  /**
   * Gets an array of the writable properties for an object.
   *
   * @return The array
   */
  public String[] getSetablePropertyNames() {
    return writablePropertyNames;
  }

  /**
   * Check to see if a class has a writable property by name.
   *
   * @param propertyName - the name of the property to check
   * @return True if the object has a writable property by the name
   */
  public boolean hasSetter(String propertyName) {
    return setMethods.containsKey(propertyName);
  }

  /**
   * Check to see if a class has a readable property by name.
   *
   * @param propertyName - the name of the property to check
   * @return True if the object has a readable property by the name
   */
  public boolean hasGetter(String propertyName) {
    return getMethods.containsKey(propertyName);
  }

  public String findPropertyName(String name) {
    return caseInsensitivePropertyMap.get(name.toUpperCase(Locale.ENGLISH));
  }
}
