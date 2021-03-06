/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2010-2013 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * http://glassfish.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package org.glassfish.jersey.server.internal.routing;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.container.ContainerResponseFilter;
import javax.ws.rs.container.DynamicFeature;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.ext.ReaderInterceptor;
import javax.ws.rs.ext.WriterInterceptor;

import javax.inject.Inject;

import org.glassfish.jersey.message.MessageBodyWorkers;
import org.glassfish.jersey.model.internal.RankedProvider;
import org.glassfish.jersey.process.Inflector;
import org.glassfish.jersey.server.ContainerRequest;
import org.glassfish.jersey.server.ContainerResponse;
import org.glassfish.jersey.server.internal.routing.RouterBinder.RootRouteBuilder;
import org.glassfish.jersey.server.internal.routing.RouterBinder.RouteBuilder;
import org.glassfish.jersey.server.internal.routing.RouterBinder.RouteToPathBuilder;
import org.glassfish.jersey.server.model.Resource;
import org.glassfish.jersey.server.model.ResourceMethod;
import org.glassfish.jersey.server.model.ResourceMethodInvoker;
import org.glassfish.jersey.uri.PathPattern;

import org.glassfish.hk2.api.ServiceLocator;

import com.google.common.base.Function;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

/**
 * This is a common base for root resource and sub-resource runtime model
 * builder.
 *
 * @author Marek Potociar (marek.potociar at oracle.com)
 */
public final class RuntimeModelBuilder {

    private final RootRouteBuilder<PathPattern> rootBuilder;
    private final ResourceMethodInvoker.Builder resourceMethodInvokerBuilder;
    private final ServiceLocator locator;
    private final PushMethodHandlerRouter.Builder pushHandlerAcceptorBuilder;
    private final MethodSelectingRouter.Builder methodSelectingAcceptorBuilder;
    private final MessageBodyWorkers workers;

    private MultivaluedMap<Class<? extends Annotation>, RankedProvider<ContainerRequestFilter>> nameBoundRequestFilters;
    private MultivaluedMap<Class<? extends Annotation>, RankedProvider<ContainerResponseFilter>> nameBoundResponseFilters;
    private Iterable<RankedProvider<ReaderInterceptor>> globalReaderInterceptors;
    private Iterable<RankedProvider<WriterInterceptor>> globalWriterInterceptors;
    private MultivaluedMap<Class<? extends Annotation>, RankedProvider<ReaderInterceptor>> nameBoundReaderInterceptors;
    private MultivaluedMap<Class<? extends Annotation>, RankedProvider<WriterInterceptor>> nameBoundWriterInterceptors;
    private Iterable<DynamicFeature> dynamicFeatures;

    /**
     * A sorted map of closed resource path patterns to the list of (root) resource method
     * acceptors registered for the patterns.
     */
    private TreeMap<PathPattern, List<MethodAcceptorPair>> rootAcceptors =
            Maps.newTreeMap(PathPattern.COMPARATOR);

    /**
     * A sorted map of open resource path patterns to the sub-resource method and locator
     * acceptors registered under the resource path patterns.
     * <p/>
     * The sub-resource method and locator acceptors are represented also by a
     * common sorted map of open method path pattern to the list of method acceptors
     * registered on that open pattern.
     * <p/>
     * Note that also sub-resource methods are mapped under an open pattern so that
     * the method path pattern (map key) comparison can work properly. The open
     * paths on the sub-resource methods are replaced with the correct closed ones
     * during the runtime creation model, before the method acceptors are routed.
     */
    private TreeMap<PathPattern, TreeMap<PathPattern, List<MethodAcceptorPair>>> subResourceAcceptors =
            Maps.newTreeMap(PathPattern.COMPARATOR);


    /**
     * Injection constructor.
     *
     * @param rootBuilder root router builder.
     * @param resourceMethodInvokerBuilder method invoker builder.
     * @param locator HK2 service locator.
     * @param pushHandlerAcceptorBuilder push handler acceptor builder.
     * @param methodSelectingAcceptorBuilder method selecting acceptor builder.
     * @param workers message body workers.
     */
    @Inject
    public RuntimeModelBuilder(
            final RootRouteBuilder<PathPattern> rootBuilder,
            final ResourceMethodInvoker.Builder resourceMethodInvokerBuilder,
            final ServiceLocator locator,
            final PushMethodHandlerRouter.Builder pushHandlerAcceptorBuilder,
            final MethodSelectingRouter.Builder methodSelectingAcceptorBuilder,
            final MessageBodyWorkers workers) {
        this.rootBuilder = rootBuilder;
        this.resourceMethodInvokerBuilder = resourceMethodInvokerBuilder;
        this.locator = locator;
        this.pushHandlerAcceptorBuilder = pushHandlerAcceptorBuilder;
        this.methodSelectingAcceptorBuilder = methodSelectingAcceptorBuilder;
        this.workers = workers;
    }

    private RuntimeModelBuilder(RuntimeModelBuilder original) {
        this.rootBuilder = original.rootBuilder;
        this.resourceMethodInvokerBuilder = original.resourceMethodInvokerBuilder;
        this.locator = original.locator;
        this.pushHandlerAcceptorBuilder = original.pushHandlerAcceptorBuilder;
        this.methodSelectingAcceptorBuilder = original.methodSelectingAcceptorBuilder;
        this.workers = original.workers;

        this.nameBoundRequestFilters = original.nameBoundRequestFilters;
        this.nameBoundResponseFilters = original.nameBoundResponseFilters;
        this.globalReaderInterceptors = original.globalReaderInterceptors;
        this.globalWriterInterceptors = original.globalWriterInterceptors;
        this.nameBoundReaderInterceptors = original.nameBoundReaderInterceptors;
        this.nameBoundWriterInterceptors = original.nameBoundWriterInterceptors;
        this.dynamicFeatures = original.dynamicFeatures;
    }

    /**
     * Create a copy of the runtime model builder.
     *
     * @return copy of the runtime model builder.
     */
    public RuntimeModelBuilder copy() {
        return new RuntimeModelBuilder(this);
    }

    /**
     * Process a single resource model and add it to the currently build runtime
     * routing and accepting model.
     *
     * @param resource resource model to be processed.
     * @param subResourceMode if {@code true}, all resources will be processed as sub-resources.
     */
    public void process(final Resource resource,
                        final boolean subResourceMode) {

        if (!(resource.isRootResource() || subResourceMode)) {
            // ignore sub-resources if not in a sub-resource modelling mode.
            return;
        }

        final PushMatchedResourceRouter pushMatchedResourceRouter = locator.createAndInitialize(PushMatchedResourceRouter.Builder.class)
                .setResource(resource).build();

        // prepare & add resource method acceptors
        if (!resource.getAllMethods().isEmpty()) {
            storeResourceMethods(resource, subResourceMode, pushMatchedResourceRouter);
        }

        // prepare & add sub-resource method and locator acceptors.
        if (!resource.getChildResources().isEmpty()) {
            storeChildResourceMethods(resource, subResourceMode, pushMatchedResourceRouter);
        }
    }

    private void storeChildResourceMethods(Resource resource, boolean subResourceMode, PushMatchedResourceRouter
            pushMatchedResourceRouter) {
        final PathPattern resourcePath =
                (subResourceMode) ? PathPattern.OPEN_ROOT_PATH_PATTERN : resource.getPathPattern();

        final TreeMap<PathPattern, List<MethodAcceptorPair>> sameResourcePathMap = getPatternAcceptorListMap(resource,
                resourcePath);
        for (Resource child : resource.getChildResources()) {
            final PushMatchedResourceRouter childPushMatchedResourceRouter = locator.createAndInitialize(PushMatchedResourceRouter
                    .Builder.class).setResource(child).setChildResourceMode(true).build();
            PathPattern childRelativePath = new PathPattern(child.getPath());

            List<MethodAcceptorPair> samePathMethodAcceptorPairs = getAcceptorList(sameResourcePathMap,
                    childRelativePath);

            for (ResourceMethod resourceMethod : child.getAllMethods()) {
                samePathMethodAcceptorPairs.add(
                        new MethodAcceptorPair(resourceMethod, child, pushMatchedResourceRouter, childPushMatchedResourceRouter,
                                createSingleMethodAcceptor(resourceMethod,
                                        subResourceMode)));
            }
        }
    }

    private void storeResourceMethods(final Resource resource, final boolean subResourceMode,
                                      final PushMatchedResourceRouter pushMatchedResourceRouter) {

        if (!resource.getResourceMethods().isEmpty()) {
            final PathPattern closedResourcePathPattern =
                    (subResourceMode) ? PathPattern.END_OF_PATH_PATTERN : PathPattern.asClosed(resource.getPathPattern());

            List<MethodAcceptorPair> sameResourcePathList = getAcceptorList(rootAcceptors, closedResourcePathPattern);

            sameResourcePathList.addAll(Lists.transform(resource.getResourceMethods(),
                    new Function<ResourceMethod, MethodAcceptorPair>() {
                        @Override
                        public MethodAcceptorPair apply(ResourceMethod methodModel) {
                            return new MethodAcceptorPair(methodModel, resource, pushMatchedResourceRouter,
                                    createSingleMethodAcceptor(methodModel, subResourceMode));
                        }
                    }));
        }

        final ResourceMethod resourceLocator = resource.getResourceLocator();
        if (resourceLocator != null) {
            final TreeMap<PathPattern, List<MethodAcceptorPair>> sameResourcePathMap = getPatternAcceptorListMap(resource,
                    resource
                            .getPathPattern());

            List<MethodAcceptorPair> locatorList = getAcceptorList(sameResourcePathMap, PathPattern.OPEN_ROOT_PATH_PATTERN);

            locatorList.add(
                    new MethodAcceptorPair(resourceLocator, resource, pushMatchedResourceRouter,
                            createSingleMethodAcceptor(resourceLocator,
                                    subResourceMode)));
        }
    }

    private TreeMap<PathPattern, List<MethodAcceptorPair>> getPatternAcceptorListMap(Resource resource, PathPattern pathPattern) {
        TreeMap<PathPattern, List<MethodAcceptorPair>> sameResourcePathMap = subResourceAcceptors.get(pathPattern);
        if (sameResourcePathMap == null) {
            sameResourcePathMap = Maps.newTreeMap(PathPattern.COMPARATOR);
            subResourceAcceptors.put(pathPattern, sameResourcePathMap);
        }

        return sameResourcePathMap;
    }

    private List<MethodAcceptorPair> getAcceptorList(TreeMap<PathPattern, List<MethodAcceptorPair>>
                                                             sameResourcePathMap, PathPattern childRelativePath) {
        List<MethodAcceptorPair> samePathMethodAcceptorPairs = sameResourcePathMap.get(childRelativePath);
        if (samePathMethodAcceptorPairs == null) {
            samePathMethodAcceptorPairs = Lists.newLinkedList();
            sameResourcePathMap.put(childRelativePath, samePathMethodAcceptorPairs);
        }
        return samePathMethodAcceptorPairs;
    }


    private Router createSingleMethodAcceptor(final ResourceMethod resourceMethod, final boolean subResourceMode) {
        Router methodAcceptor = null;
        switch (resourceMethod.getType()) {
            case RESOURCE_METHOD:
            case SUB_RESOURCE_METHOD:
                methodAcceptor = Routers.asTreeAcceptor(createInflector(resourceMethod));
                break;
            case SUB_RESOURCE_LOCATOR:
                methodAcceptor = new SubResourceLocatorRouter(locator, this, resourceMethod);
                break;
        }

        // TODO: solve this via instance-based method handler model?
        return pushHandlerAcceptorBuilder.build(resourceMethod.getInvocable().getHandler(), methodAcceptor, subResourceMode);
    }


    private Inflector<ContainerRequest, ContainerResponse> createInflector(final ResourceMethod method) {

        return resourceMethodInvokerBuilder.build(
                method,
                nameBoundRequestFilters,
                nameBoundResponseFilters,
                globalReaderInterceptors,
                globalWriterInterceptors,
                nameBoundReaderInterceptors,
                nameBoundWriterInterceptors,
                dynamicFeatures
        );
    }

    private Router createRootTreeAcceptor(RouteToPathBuilder<PathPattern> lastRoutedBuilder, boolean subResourceMode) {
        final Router routingRoot;
        if (lastRoutedBuilder != null) {
            routingRoot = lastRoutedBuilder.build();
        } else {
            /**
             * Create an empty routing root that accepts any request, does not do
             * anything and does not return any inflector. This will cause 404 being
             * returned for every request.
             */
            routingRoot = Routers.acceptingTree(new Function<ContainerRequest, ContainerRequest>() {

                @Override
                public ContainerRequest apply(ContainerRequest input) {
                    return input;
                }

            }).build();
        }

        if (subResourceMode) {
            return routingRoot;
        } else {
            return rootBuilder.root(routingRoot);
        }
    }

    private RouteToPathBuilder<PathPattern> routeMethodAcceptor(
            final RouteToPathBuilder<PathPattern> lastRoutedBuilder,
            final PathPattern pathPattern,
            final Router uriPushingAcceptor,
            final Router methodAcceptor, boolean subResourceMode) {

        if (subResourceMode) {
            return routedBuilder(lastRoutedBuilder).route(pathPattern)
                    .to(methodAcceptor);
        } else {

            return routedBuilder(lastRoutedBuilder).route(pathPattern)
                    .to(uriPushingAcceptor)
                    .to(methodAcceptor);
        }
    }

    /**
     * Build a runtime model.
     *
     * @param subResourceMode if {@code true}, all resources will be processed as sub-resources.
     * @return runtime request routing root.
     */
    public Router buildModel(boolean subResourceMode) {
        final PushMatchedUriRouter uriPushingRouter = locator.createAndInitialize(PushMatchedUriRouter.class);
        RouteToPathBuilder<PathPattern> lastRoutedBuilder = null;

        // route resource method acceptors
        if (!rootAcceptors.isEmpty()) {
            for (Map.Entry<PathPattern, List<MethodAcceptorPair>> entry : rootAcceptors.entrySet()) {
                final PathPattern closedResourcePathPattern = entry.getKey();
                List<MethodAcceptorPair> methodAcceptorPairs = entry.getValue();

                lastRoutedBuilder = routeMethodAcceptor(
                        lastRoutedBuilder,
                        closedResourcePathPattern,
                        uriPushingRouter,
                        methodSelectingAcceptorBuilder.build(workers, methodAcceptorPairs), subResourceMode);
            }
            rootAcceptors.clear();
        }

        // route sub-resource method and locator acceptors
        if (!subResourceAcceptors.isEmpty()) {
            for (Map.Entry<PathPattern, TreeMap<PathPattern, List<MethodAcceptorPair>>> singleResourcePathEntry :
                    subResourceAcceptors.entrySet()) {
                RouteToPathBuilder<PathPattern> srRoutedBuilder = null;

                final TreeMap<PathPattern, List<MethodAcceptorPair>> subAcceptors = singleResourcePathEntry.getValue();
                for (Map.Entry<PathPattern, List<MethodAcceptorPair>> singlePathEntry
                        : subAcceptors.entrySet()) {
                    Resource childResource = null;

                    // there can be multiple sub-resource methods on the same path
                    // but only a single sub-resource locator.
                    List<MethodAcceptorPair> resourceMethods = Lists.newLinkedList();
                    MethodAcceptorPair resourceLocator = null;

                    final List<MethodAcceptorPair> methodAcceptorPairs = singlePathEntry.getValue();
                    for (MethodAcceptorPair methodAcceptorPair : methodAcceptorPairs) {
                        if (childResource == null) {
                            childResource = methodAcceptorPair.resource;
                        }

                        if (methodAcceptorPair.model.getType() == ResourceMethod.JaxrsType.RESOURCE_METHOD) {
                            resourceMethods.add(methodAcceptorPair);
                        } else {
                            resourceLocator = methodAcceptorPair;
                        }
                    }
                    final PathPattern childResourcePathPattern = singlePathEntry.getKey();

                    if (!resourceMethods.isEmpty()) {
                        final PathPattern subResourceMethodPath = PathPattern.asClosed(childResourcePathPattern);
                        srRoutedBuilder = routedBuilder(srRoutedBuilder).route(subResourceMethodPath)
                                .to(uriPushingRouter)
                                .to(methodSelectingAcceptorBuilder.build(workers, resourceMethods));
                    }

                    if (resourceLocator != null) {
                        srRoutedBuilder = routedBuilder(srRoutedBuilder).route(childResourcePathPattern)
                                .to(uriPushingRouter);
                        for (Router router : resourceLocator.router) {
                            srRoutedBuilder = srRoutedBuilder.to(router);
                        }
                    }
                }
                assert srRoutedBuilder != null;


                lastRoutedBuilder = routeMethodAcceptor(
                        lastRoutedBuilder, singleResourcePathEntry.getKey(), uriPushingRouter,
                        srRoutedBuilder.build(), subResourceMode);
            }
            subResourceAcceptors.clear();
        }
        return createRootTreeAcceptor(lastRoutedBuilder, subResourceMode);
    }

    private RouteBuilder<PathPattern> routedBuilder(RouteToPathBuilder<PathPattern> lastRoutedBuilder) {
        return lastRoutedBuilder == null ? rootBuilder : lastRoutedBuilder;
    }

    /**
     * Set global reader and writer interceptors.
     *
     * @param readerInterceptors global reader interceptors.
     * @param writerInterceptors global writer interceptors.
     */
    public void setGlobalInterceptors(Iterable<RankedProvider<ReaderInterceptor>> readerInterceptors,
                                      Iterable<RankedProvider<WriterInterceptor>> writerInterceptors) {
        this.globalReaderInterceptors = readerInterceptors;
        this.globalWriterInterceptors = writerInterceptors;
    }

    /**
     * Set the name bound filters and dynamic binders.
     *
     * @param nameBoundRequestFilters name bound request filters.
     * @param nameBoundResponseFilters name bound response filters.
     * @param nameBoundReaderInterceptors name bound reader interceptors.
     * @param nameBoundWriterInterceptors name bound writer interceptors.
     * @param dynamicFeatures dynamic features.
     */
    public void setBoundProviders(
            MultivaluedMap<Class<? extends Annotation>, RankedProvider<ContainerRequestFilter>> nameBoundRequestFilters,
            MultivaluedMap<Class<? extends Annotation>, RankedProvider<ContainerResponseFilter>> nameBoundResponseFilters,
            MultivaluedMap<Class<? extends Annotation>, RankedProvider<ReaderInterceptor>> nameBoundReaderInterceptors,
            MultivaluedMap<Class<? extends Annotation>, RankedProvider<WriterInterceptor>> nameBoundWriterInterceptors,
            Iterable<DynamicFeature> dynamicFeatures
    ) {
        this.nameBoundRequestFilters = nameBoundRequestFilters;
        this.nameBoundResponseFilters = nameBoundResponseFilters;
        this.nameBoundReaderInterceptors = nameBoundReaderInterceptors;
        this.nameBoundWriterInterceptors = nameBoundWriterInterceptors;
        this.dynamicFeatures = dynamicFeatures;
    }
}
