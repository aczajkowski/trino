/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.server;

import com.google.common.reflect.TypeToken;
import io.airlift.slice.OutputStreamSliceOutput;
import io.airlift.slice.SliceOutput;
import io.trino.FeaturesConfig;
import io.trino.FeaturesConfig.DataIntegrityVerification;
import io.trino.execution.buffer.SerializedPage;

import javax.inject.Inject;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.ext.MessageBodyWriter;
import javax.ws.rs.ext.Provider;

import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.List;

import static io.trino.TrinoMediaTypes.TRINO_PAGES;
import static io.trino.execution.buffer.PagesSerdeUtil.NO_CHECKSUM;
import static io.trino.execution.buffer.PagesSerdeUtil.calculateChecksum;
import static io.trino.execution.buffer.PagesSerdeUtil.writeSerializedPages;
import static java.util.Objects.requireNonNull;

@Provider
@Produces(TRINO_PAGES)
public class PagesResponseWriter
        implements MessageBodyWriter<List<SerializedPage>>
{
    public static final int SERIALIZED_PAGES_MAGIC = 0xfea4f001;

    private static final MediaType TRINO_PAGES_TYPE = MediaType.valueOf(TRINO_PAGES);
    private static final Type LIST_GENERIC_TOKEN;

    static {
        try {
            LIST_GENERIC_TOKEN = List.class.getMethod("get", int.class).getGenericReturnType();
        }
        catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    private final boolean dataIntegrityVerificationEnabled;

    @Inject
    public PagesResponseWriter(FeaturesConfig featuresConfig)
    {
        requireNonNull(featuresConfig, "featuresConfig is null");
        this.dataIntegrityVerificationEnabled = featuresConfig.getExchangeDataIntegrityVerification() != DataIntegrityVerification.NONE;
    }

    @Override
    public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType)
    {
        return List.class.isAssignableFrom(type) &&
                TypeToken.of(genericType).resolveType(LIST_GENERIC_TOKEN).getRawType().equals(SerializedPage.class) &&
                mediaType.isCompatible(TRINO_PAGES_TYPE);
    }

    @Override
    public long getSize(List<SerializedPage> serializedPages, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType)
    {
        return -1;
    }

    @Override
    public void writeTo(List<SerializedPage> serializedPages,
            Class<?> type,
            Type genericType,
            Annotation[] annotations,
            MediaType mediaType,
            MultivaluedMap<String, Object> httpHeaders,
            OutputStream output)
            throws IOException, WebApplicationException
    {
        try {
            SliceOutput sliceOutput = new OutputStreamSliceOutput(output);
            sliceOutput.writeInt(SERIALIZED_PAGES_MAGIC);
            sliceOutput.writeLong(dataIntegrityVerificationEnabled ? calculateChecksum(serializedPages) : NO_CHECKSUM);
            sliceOutput.writeInt(serializedPages.size());
            writeSerializedPages(sliceOutput, serializedPages);
            // We use flush instead of close, because the underlying stream would be closed and that is not allowed.
            sliceOutput.flush();
        }
        catch (UncheckedIOException e) {
            // EOF exception occurs when the client disconnects while writing data
            // This is not a "server" problem so we don't want to log this
            if (!(e.getCause() instanceof EOFException)) {
                throw e;
            }
        }
    }
}
