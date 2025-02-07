/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.cxf.systest.hc5.jaxrs;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javax.activation.DataHandler;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.StreamingOutput;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriInfo;

import org.apache.cxf.common.util.StringUtils;
import org.apache.cxf.helpers.IOUtils;
import org.apache.cxf.jaxrs.ext.multipart.Attachment;
import org.apache.cxf.jaxrs.ext.multipart.MultipartBody;

@Path("/file-store")
public class FileStore {
    private final ConcurrentMap<String, byte[]> store = new ConcurrentHashMap<>();
    @Context private HttpHeaders headers;

    @POST
    @Path("/stream")
    @Consumes("*/*")

    @SuppressWarnings("PMD.UseTryWithResources")
    public Response addFile(@QueryParam("chunked") boolean chunked, InputStream in) throws IOException {
        String transferEncoding = headers.getHeaderString("Transfer-Encoding");

        if (chunked != Objects.equals("chunked", transferEncoding)) {
            throw new WebApplicationException(Status.EXPECTATION_FAILED);
        }

        try {
            if (chunked) {
                return Response.ok(new StreamingOutput() {
                    @Override
                    public void write(OutputStream out) throws IOException, WebApplicationException {
                        IOUtils.copyAndCloseInput(in, out);
                    }
                }).build();
            } else {
                // Make sure we have small amount of data for chunking to not kick in
                final byte[] content = IOUtils.readBytesFromStream(in); 
                return Response.ok(Arrays.copyOf(content, content.length / 10)).build();
            }
        } finally {
            if (in != null) {
                in.close();
            }
        }
    }

    @POST
    @Consumes("multipart/form-data")
    public void addFile(@QueryParam("chunked") boolean chunked, 
            @Suspended final AsyncResponse response, @Context final UriInfo uri, final MultipartBody body)  {

        String transferEncoding = headers.getHeaderString("Transfer-Encoding");
        if (chunked != Objects.equals("chunked", transferEncoding)) {
            response.resume(Response.status(Status.EXPECTATION_FAILED).build());
            return;
        }

        for (final Attachment attachment: body.getAllAttachments()) {
            final DataHandler handler = attachment.getDataHandler();

            if (handler != null) {
                final String source = handler.getName();
                if (StringUtils.isEmpty(source)) {
                    response.resume(Response.status(Status.BAD_REQUEST).build());
                    return;
                }

                try {
                    if (store.containsKey(source)) {
                        response.resume(Response.status(Status.CONFLICT).build());
                        return;
                    }

                    final byte[] content = IOUtils.readBytesFromStream(handler.getInputStream());
                    if (store.putIfAbsent(source, content) != null) {
                        response.resume(Response.status(Status.CONFLICT).build());
                        return;
                    }
                    
                    if (response.isSuspended()) {
                        final StreamingOutput stream = new StreamingOutput() {
                            @Override
                            public void write(OutputStream os) throws IOException, WebApplicationException {
                                if (chunked) {
                                    // Make sure we have enough data for chunking to kick in
                                    for (int i = 0; i < 10; ++i) {
                                        os.write(content);
                                    }
                                } else {
                                    os.write(content);
                                }
                            }
                        };
                        response.resume(Response.created(uri.getRequestUriBuilder()
                                .path(source).build()).entity(stream)
                                .build());
                    }

                } catch (final Exception ex) {
                    response.resume(Response.serverError().build());
                }

            }
        }
    }

    @GET
    @Consumes("multipart/form-data")
    public void getFile(@QueryParam("chunked") boolean chunked, @QueryParam("filename") String source,
            @Suspended final AsyncResponse response) {

        if (StringUtils.isEmpty(source)) {
            response.resume(Response.status(Status.BAD_REQUEST).build());
            return;
        }

        try {
            if (!store.containsKey(source)) {
                response.resume(Response.status(Status.NOT_FOUND).build());
                return;
            }

            final byte[] content = store.get(source);
            if (response.isSuspended()) {
                final StreamingOutput stream = new StreamingOutput() {
                    @Override
                    public void write(OutputStream os) throws IOException, WebApplicationException {
                        if (chunked) {
                            // Make sure we have enough data for chunking to kick in
                            for (int i = 0; i < 10; ++i) {
                                os.write(content);
                            }
                        } else {
                            os.write(content);
                        }
                    }
                };
                response.resume(Response.ok().entity(stream).build());
            }

        } catch (final Exception ex) {
            response.resume(Response.serverError().build());
        }
    }

    @GET
    @Path("/redirect")
    public Response redirectFile(@Context UriInfo uriInfo) {
        final UriBuilder builder = uriInfo.getBaseUriBuilder().path(getClass());
        uriInfo.getQueryParameters(true).forEach((p, v) -> builder.queryParam(p, v.get(0)));

        final ResponseBuilder response = Response.status(303).header("Location", builder.build());
        return response.build();
    }
}
