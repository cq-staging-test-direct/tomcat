package org.apache.catalina.valves;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.concurrent.ScheduledExecutorService;

import org.apache.catalina.Container;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.tomcat.util.threads.ScheduledThreadPoolExecutor;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

/**
 * <p>A Valve to wrap a Filter, allowing a user to run Servlet Filters as a
 * part of the Valve chain.</p>
 *
 * <p>There are some caveats you must be aware of when using this Valve
 * to wrap a Filter:</p>
 *
 * <ul>
 *   <li>You get a <i>separate instance</i> of your Filter class distinct
 *       from any that may be instantiated within your application.</li>
 *   <li>Calls to {@link FilterConfig#getFilterName()} will return <code>null</code>.</li>
 *   <li>Calling {@link FilterConfig#getServletContext()} will return
 *       the proper ServletContext for a Valve/Filter attached to a
 *       <code>&lt;Context&gt;</code>, but will return a ServletContext
 *       which is nearly useless for any Valve/Filter specified on
 *       an <code>&lt;Engine&gt;</code> or <code>&gt;Host&lt;</code>.</li>
 *   <li>Your Filter <b>MUST NOT</b> wrap the {@link ServletRequest} or
 *       {@link ServletResponse} objects, or an exception will be thrown.</li>
 * </ul>
 *
 * @see Valve
 * @see Filter
 */
public class FilterValve
    extends ValveBase
    implements FilterConfig
{
    /**
     * The name of the Filter class that will be instantiated.
     */
    private String filterClassName;

    /**
     * The init-params for the Filter.
     */
    private HashMap<String,String> filterInitParams;

    /**
     * The instance of the Filter to be invoked.
     */
    private Filter filter;

    /**
     * The ServletContet to be provided to the Filter if it requests one.
     */
    private ServletContext application;

    /**
     * Sets the name of the class for the Filter.
     *
     * @param filterClassName The class name for the Filter.
     */
    public void setFilterClass(String filterClassName) {
        setFilterClassName(filterClassName);
    }

    /**
     * Sets the name of the class for the Filter.
     *
     * @param filterClassName The class name for the Filter.
     */
    public void setFilterClassName(String filterClassName) {
        this.filterClassName = filterClassName;
    }

    /**
     * Gets the name of the class for the Filter.
     *
     * @return The class name for the Filter.
     */
    public String getFilterClassName() {
        return filterClassName;
    }

    /**
     * Adds an initialization parameter for the Filter.
     *
     * @param paramName The name of the parameter.
     * @param paramValue The value of the parameter.
     */
    public void addInitParam(String paramName, String paramValue) {
        if(null == filterInitParams) {
            filterInitParams = new HashMap<>();
        }

        filterInitParams.put(paramName, paramValue);
    }

    /**
     * Gets the name of the Filter.
     *
     * @return <code>null</code>
     */
    @Override
    public String getFilterName() {
        return null;
    }

    /**
     * Gets the ServletContext.
     *
     * Note that this will be of limited use if the Valve/Filter is not
     * attached to a <code>&lt;Context&gt;</code>.
     */
    @Override
    public ServletContext getServletContext() {
        if(null == application) {
            throw new IllegalStateException("Filter " + filter + " has called getServletContext from FilterValve, but this FilterValve is not ");
        } else {
            return application;
        }
    }

    /**
     * Gets the initialization parameter with the specified name.
     *
     * @param name The name of the initialization parameter.
     *
     * @return The value for the initialization parameter, or
     *         <code>null</code> if there is no value for the
     *         specified initialization parameter name.
     */
    @Override
    public String getInitParameter(String name) {
        if(null == filterInitParams) {
            return null;
        } else {
            return filterInitParams.get(name);
        }
    }

    /**
     * Gets an enumeration of the names of all initialization parameters.
     *
     * @return An enumeration of the names of all initialization parameters.
     */
    @Override
    public Enumeration<String> getInitParameterNames() {
        if(null == filterInitParams) {
            return Collections.emptyEnumeration();
        } else {
            return Collections.enumeration(filterInitParams.keySet());
        }
    }

    @Override
    protected synchronized void startInternal() throws LifecycleException {
        super.startInternal();

        if(null == getFilterClassName()) {
            throw new LifecycleException("No filterClass specified for FilterValve: a filterClass is required");
        }
        Container parent = super.getContainer();
        if(parent instanceof Context) {
            application = ((Context)parent).getServletContext();
        } else {
            final ScheduledExecutorService executor = Container.getService(super.getContainer()).getServer().getUtilityExecutor();

            // I don't feel like writing a whole trivial class just for this one thing.
            application = (ServletContext)Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] { ServletContext.class },
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                            if("getAttribute".equals(method.getName())
                                    && null != args
                                    && 1 == args.length
                                    && ScheduledThreadPoolExecutor.class.getName().equals(args[0])) {
                                return executor;
                            } else {
                                throw new UnsupportedOperationException("This ServletContet is not really meant to be used.");
                            }
                        }
            });
        }

        try {
            filter = (Filter) Class.forName(getFilterClassName()).getConstructor(new Class[0]).newInstance();

            filter.init(this);
        } catch (ServletException | InstantiationException | IllegalAccessException | IllegalArgumentException | InvocationTargetException | NoSuchMethodException | SecurityException | ClassNotFoundException se) {
            throw new LifecycleException("Failed to start FilterValve for filter " + filter, se);
        }
    }

    @Override
    protected synchronized void stopInternal() throws LifecycleException {
        super.stopInternal();

        if(null != filter) {
            try {
                filter.destroy();
            } finally {
                filter = null;
            }
        }
    }

    @Override
    public void invoke(final Request request, final Response response) throws IOException, ServletException {
        // Allow our FilterChain object to share data back to us.
        //
        // FilterCallInfo captures information about what the Filter
        // ultimately did, specifically whether or not FilterChain.doFilter
        // was called, and with what arguments.
        final FilterCallInfo filterCallInfo = new FilterCallInfo();

        filter.doFilter(request, response, filterCallInfo);

        if(!filterCallInfo.stop) {
            if(request != filterCallInfo.request) {
                throw new IllegalStateException("Filter " + filter + " has illegally changed or wrapped the request");
            }

            if(response != filterCallInfo.response) {
                throw new IllegalStateException("Filter " + filter + " has illegally changed or wrapped the response");
            }

            getNext().invoke(request, response);
        }
    }

    private static final class FilterCallInfo implements FilterChain {
        private boolean stop = true;
        private ServletRequest request;
        private ServletResponse response;

        @Override
        public void doFilter(ServletRequest request, ServletResponse response) throws IOException, ServletException {
            this.request = request;
            this.response = response;
            this.stop = false;
        }
    }
}
