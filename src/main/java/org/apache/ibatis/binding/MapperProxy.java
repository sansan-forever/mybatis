/**
 * Copyright 2009-2020 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ibatis.binding;

import java.io.Serializable;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;

import org.apache.ibatis.reflection.ExceptionUtil;
import org.apache.ibatis.session.SqlSession;

/**
 * @author Clinton Begin
 * @author Eduardo Macarron
 */
public class MapperProxy<T> implements InvocationHandler, Serializable {

    private static final long serialVersionUID = -4724728412955527868L;
    private static final int ALLOWED_MODES = MethodHandles.Lookup.PRIVATE | MethodHandles.Lookup.PROTECTED
            | MethodHandles.Lookup.PACKAGE | MethodHandles.Lookup.PUBLIC;

    /**
     * MethodHandle 简介
     * 从 Java 7 开始，除了反射之外，在 java.lang.invoke 包中新增了 MethodHandle 这个类，它的基本功能与反射中的 Method 类似，
     * 但它比反射更加灵活。反射是 Java API 层面支持的一种机制，MethodHandle 则是 JVM 层支持的机制，相较而言，反射更加重量级，MethodHandle 则更轻量级，性能也比反射更好些
     */


    // 针对 JDK 8 中的特殊处理，该字段指向了 MethodHandles.Lookup 的构造方法
    private static final Constructor<Lookup> lookupConstructor;

    // ：除了 JDK 8 之外的其他 JDK 版本会使用该字段，该字段指向 MethodHandles.privateLookupIn() 方法
    private static final Method privateLookupInMethod;

    // 记录了当前 MapperProxy 关联的 SqlSession 对象。在与当前 MapperProxy 关联的代理对象中，会用该 SqlSession 访问数据库
    private final SqlSession sqlSession;

    // Mapper 接口类型，也是当前 MapperProxy 关联的代理对象实现的接口类型
    private final Class<T> mapperInterface;

    // 用于缓存 MapperMethodInvoker 对象的集合。methodCache 中的 key 是 Mapper 接口中的方法，value 是该方法对应的 MapperMethodInvoker 对象
    private final Map<Method, MapperMethodInvoker> methodCache;

    public MapperProxy(SqlSession sqlSession, Class<T> mapperInterface, Map<Method, MapperMethodInvoker> methodCache) {
        this.sqlSession = sqlSession;
        this.mapperInterface = mapperInterface;
        this.methodCache = methodCache;
    }

    static {
        Method privateLookupIn;
        try {
            privateLookupIn = MethodHandles.class.getMethod("privateLookupIn", Class.class, MethodHandles.Lookup.class);
        } catch (NoSuchMethodException e) {
            privateLookupIn = null;
        }
        privateLookupInMethod = privateLookupIn;

        Constructor<Lookup> lookup = null;
        if (privateLookupInMethod == null) {
            // JDK 1.8
            try {
                lookup = MethodHandles.Lookup.class.getDeclaredConstructor(Class.class, int.class);
                lookup.setAccessible(true);
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException(
                        "There is neither 'privateLookupIn(Class, Lookup)' nor 'Lookup(Class, int)' method in java.lang.invoke.MethodHandles.",
                        e);
            } catch (Exception e) {
                lookup = null;
            }
        }
        lookupConstructor = lookup;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        try {
            if (Object.class.equals(method.getDeclaringClass())) {
                // 如果目标方法继承自Object，则直接调用目标方法
                return method.invoke(this, args);
            } else {
                // 从缓存中获取MapperMethod对象，并执行目标方法
                return cachedInvoker(method).invoke(proxy, method, args, sqlSession);
            }
        } catch (Throwable t) {
            throw ExceptionUtil.unwrapThrowable(t);
        }
    }

    private MapperMethodInvoker cachedInvoker(Method method) throws Throwable {
        try {
            // 尝试从methodCache缓存中查询方法对应的MapperMethodInvoker
            MapperMethodInvoker invoker = methodCache.get(method);
            if (invoker != null) {
                return invoker;
            }
            // 如果方法在缓存中没有对应的MapperMethodInvoker，则进行创建
            return methodCache.computeIfAbsent(method, m -> {
                if (m.isDefault()) { // 针对default方法的处理
                    try {
                        // 这里根据JDK版本的不同，获取方法对应的MethodHandle的方式也有所不同
                        // 在JDK 8中使用的是lookupConstructor字段，而在JDK 9中使用的是
                        // privateLookupInMethod字段。获取到MethodHandle之后，会使用
                        // DefaultMethodInvoker进行封装
                        if (privateLookupInMethod == null) {
                            return new DefaultMethodInvoker(getMethodHandleJava8(method));
                        } else {
                            return new DefaultMethodInvoker(getMethodHandleJava9(method));
                        }
                    } catch (IllegalAccessException | InstantiationException | InvocationTargetException
                            | NoSuchMethodException e) {
                        throw new RuntimeException(e);
                    }
                } else {
                    // 对于其他方法，会创建MapperMethod并使用PlainMethodInvoker封装
                    return new PlainMethodInvoker(
                            new MapperMethod(mapperInterface, method, sqlSession.getConfiguration()));
                }
            });
        } catch (RuntimeException re) {
            Throwable cause = re.getCause();
            throw cause == null ? re : cause;
        }
    }

    private MethodHandle getMethodHandleJava9(Method method)
            throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        final Class<?> declaringClass = method.getDeclaringClass();
        return ((Lookup) privateLookupInMethod.invoke(null, declaringClass, MethodHandles.lookup())).findSpecial(
                declaringClass, method.getName(), MethodType.methodType(method.getReturnType(), method.getParameterTypes()),
                declaringClass);
    }

    private MethodHandle getMethodHandleJava8(Method method)
            throws IllegalAccessException, InstantiationException, InvocationTargetException {
        final Class<?> declaringClass = method.getDeclaringClass();
        return lookupConstructor.newInstance(declaringClass, ALLOWED_MODES).unreflectSpecial(method, declaringClass);
    }

    interface MapperMethodInvoker {
        Object invoke(Object proxy, Method method, Object[] args, SqlSession sqlSession) throws Throwable;
    }

    private static class PlainMethodInvoker implements MapperMethodInvoker {
        private final MapperMethod mapperMethod;

        public PlainMethodInvoker(MapperMethod mapperMethod) {
            super();
            this.mapperMethod = mapperMethod;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args, SqlSession sqlSession) throws Throwable {
            // 直接执行MapperMethod.execute()方法完成方法调用
            return mapperMethod.execute(sqlSession, args);
        }
    }

    private static class DefaultMethodInvoker implements MapperMethodInvoker {
        private final MethodHandle methodHandle;

        public DefaultMethodInvoker(MethodHandle methodHandle) {
            super();
            this.methodHandle = methodHandle;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args, SqlSession sqlSession) throws Throwable {
            return methodHandle.bindTo(proxy).invokeWithArguments(args);
        }
    }
}
