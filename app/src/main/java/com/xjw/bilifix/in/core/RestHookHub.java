package com.xjw.bilifix.in.core;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.libxposed.api.XposedInterface;

public final class RestHookHub {
    private static final String REQUEST_CLASS = "okhttp3.a0";
    private static final String INTERCEPTOR_CLASS = "com.bilibili.okretro.interceptor.a";
    private static final String CONFIG_CLASS = "dc.a";

    @FunctionalInterface
    public interface Next {
        Object proceed(Object[] args) throws Throwable;
    }

    public interface RequestScope {
        boolean matches(String url, String verb);

        Object around(String url, String verb, XposedInterface.Chain chain, Object[] args, Next next)
                throws Throwable;
    }

    @FunctionalInterface
    public interface CommonParamListener {
        void afterAddCommonParam(Object parameters) throws Throwable;
    }

    @FunctionalInterface
    public interface UserAgentRewriter {
        String rewrite(String userAgent) throws Throwable;
    }

    private final HookApi module;
    private final ClassLoader classLoader;
    private final List<RequestScope> requestScopes = new ArrayList<>();
    private final List<CommonParamListener> commonParamListeners = new ArrayList<>();
    private final List<UserAgentRewriter> userAgentRewriters = new ArrayList<>();
    private boolean installed;

    public RestHookHub(HookApi module, ClassLoader classLoader) {
        this.module = module;
        this.classLoader = classLoader;
    }

    public void addRequestScope(RequestScope scope) {
        ensureNotInstalled();
        requestScopes.add(scope);
    }

    public void addCommonParamListener(CommonParamListener listener) {
        ensureNotInstalled();
        commonParamListeners.add(listener);
    }

    public void addUserAgentRewriter(UserAgentRewriter rewriter) {
        ensureNotInstalled();
        userAgentRewriters.add(rewriter);
    }

    public void install() {
        installed = true;
        if (requestScopes.isEmpty() && commonParamListeners.isEmpty()
                && userAgentRewriters.isEmpty()) {
            return;
        }
        installGroup("REST request identity", this::installHooks);
        module.info("REST shared hooks installed: requestScopes=" + requestScopes.size()
                + " commonParamListeners=" + commonParamListeners.size()
                + " userAgentRewriters=" + userAgentRewriters.size());
    }

    private void installHooks() throws Throwable {
        Class<?> requestClass = module.load(classLoader, REQUEST_CLASS);
        Class<?> interceptorClass = module.load(classLoader, INTERCEPTOR_CLASS);
        Class<?> configClass = module.load(classLoader, CONFIG_CLASS);

        Method requestUrl = module.declaredMethod(requestClass, "l");
        Method requestVerb = module.declaredMethod(requestClass, "h");
        Method intercept = module.declaredMethod(interceptorClass, "intercept", requestClass);
        Method addCommonParam = module.declaredMethod(
                interceptorClass, "addCommonParam", Map.class);
        Method userAgent = module.declaredMethod(configClass, "c");

        module.deoptimizeFeatureMethod(intercept);
        module.deoptimizeFeatureMethod(addCommonParam);

        if (!requestScopes.isEmpty()) {
            module.addHook("REST shared request scope", intercept, chain -> {
                Object request = chain.getArg(0);
                String url = String.valueOf(module.invoke(requestUrl, request));
                String verb = String.valueOf(module.invoke(requestVerb, request));
                return runRequest(url, verb, chain, 0, null);
            });
        }

        if (!commonParamListeners.isEmpty()) {
            module.addHook("REST shared common parameters", addCommonParam, chain -> {
                Object result = chain.proceed();
                Object parameters = chain.getArg(0);
                for (CommonParamListener listener : commonParamListeners) {
                    try {
                        listener.afterAddCommonParam(parameters);
                    } catch (Throwable throwable) {
                        module.error("REST shared common parameter listener failed", throwable);
                    }
                }
                return result;
            });
        }

        if (!userAgentRewriters.isEmpty()) {
            module.addHook("REST shared user agent", userAgent, chain -> {
                Object result = chain.proceed();
                if (!(result instanceof String)) {
                    return result;
                }
                String value = (String) result;
                for (UserAgentRewriter rewriter : userAgentRewriters) {
                    try {
                        String rewritten = rewriter.rewrite(value);
                        if (rewritten != null) {
                            value = rewritten;
                        }
                    } catch (Throwable throwable) {
                        module.error("REST shared user agent rewriter failed", throwable);
                    }
                }
                return value;
            });
        }
    }

    private Object runRequest(
            String url, String verb, XposedInterface.Chain chain, int index, Object[] args)
            throws Throwable {
        List<RequestScope> scopes = requestScopes;
        while (index < scopes.size() && !scopes.get(index).matches(url, verb)) {
            index++;
        }
        if (index >= scopes.size()) {
            return args == null ? chain.proceed() : chain.proceed(args);
        }
        int next = index + 1;
        return scopes.get(index).around(url, verb, chain, args,
                nextArgs -> runRequest(url, verb, chain, next, nextArgs));
    }

    private void ensureNotInstalled() {
        if (installed) {
            throw new IllegalStateException("RestHookHub already installed");
        }
    }

    private void installGroup(String label, ThrowingAction action) {
        try {
            action.run();
            module.info("REST shared hook group ready: " + label);
        } catch (Throwable throwable) {
            module.error("REST shared hook group unavailable: " + label, throwable);
        }
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Throwable;
    }
}
