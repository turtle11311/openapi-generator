/*
 * Copyright 2018 OpenAPI-Generator Contributors (https://openapi-generator.tech)
 * Copyright 2018 SmartBear Software
 *
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

package org.openapitools.codegen;

import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.openapitools.codegen.utils.ModelUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class InlineModelResolver {
    private OpenAPI openapi;
    private Map<String, String> generatedSignature = new HashMap<String, String>();
    static Logger LOGGER = LoggerFactory.getLogger(InlineModelResolver.class);

    void flatten(OpenAPI openapi) {
        this.openapi = openapi;

        if (openapi.getComponents() == null) {
            openapi.setComponents(new Components());
        }

        if (openapi.getComponents().getSchemas() == null) {
            openapi.getComponents().setSchemas(new HashMap<>());
        }

        flattenPaths();
        flattenComponents();
    }

    /**
     * Flatten inline models in Paths
     */
    private void flattenPaths() {
        Paths paths = openapi.getPaths();
        if (paths == null) {
            return;
        }

        for (String pathname : paths.keySet()) {
            PathItem path = paths.get(pathname);
            for (Operation operation : path.readOperations()) {
                RequestBody requestBody = ModelUtils.getReferencedRequestBody(openapi, operation.getRequestBody());
                flattenRequestBody(operation, requestBody);
                flattenParameters(operation);
                flattenResponses(operation);
            }
        }
    }

    /**
     * Flatten inline models in RequestBody
     *
     * @param operation target operation
     * @param requestBody target requestBody

     */
    private void flattenRequestBody(Operation operation, RequestBody requestBody) {
        if (requestBody == null) {
            return;
        }

        Content content = requestBody.getContent();
        content.forEach((contentType, mediaType) -> {
            Schema requestBodySchema = mediaType.getSchema();
            if (isNeedInlineSchema(requestBodySchema)) {
                String requestBodySchemaName = resolveModelName(requestBodySchema.getTitle(), operation.getOperationId() + "_requestBody");
                flattenSchema(requestBodySchema, requestBodySchemaName);

                openapi.getComponents().addSchemas(requestBodySchemaName, requestBodySchema);
                mediaType.schema(new Schema().$ref(requestBodySchemaName));
            }
        });

    }

    /**
     * Flatten inline models in parameters
     *
     * @param operation target operation
     */
    private void flattenParameters(Operation operation) {
        List<Parameter> parameters = operation.getParameters();
        if (parameters == null) {
            return;
        }

        for (Parameter parameter : parameters) {
            Parameter referencedParameter = ModelUtils.getReferencedParameter(openapi, parameter);
            Schema parameterModel = ModelUtils.getReferencedSchema(openapi, referencedParameter.getSchema());
            if (parameterModel == null) {
                parameterModel = referencedParameter
                        .getContent().values().iterator().next().getSchema();
                referencedParameter.setContent(null);
                referencedParameter.setSchema(parameterModel);
            }
            String title = parameterModel.getTitle();
            String s = parameterModel.getName() +
                    parameter.getName();
            String parameterModelName = resolveModelName(title,
                    s);
            flattenSchema(parameterModel, parameterModelName);
        }
    }

    /**
     * Flatten inline models in ApiResponses
     *
     * @param operation target operation
     */
    private void flattenResponses(Operation operation) {
        ApiResponses responses = operation.getResponses();
        if (responses == null) {
            return;
        }

        for (String statusCode : responses.keySet()) {
            ApiResponse response = responses.get(statusCode);
            if (ModelUtils.getSchemaFromResponse(response) == null) {
                continue;
            }

            response.getContent().forEach((contentType, MediaType) -> {
                Schema responseModel = MediaType.getSchema();
                if (isNeedInlineSchema(responseModel)) {
                    String responseModelName = resolveModelName(responseModel.getTitle(),
                            operation.getOperationId() + "_response_" + statusCode);
                    flattenSchema(responseModel, responseModelName);

                    openapi.getComponents().addSchemas(responseModelName, responseModel);
                    MediaType.schema(new Schema().$ref(responseModelName));
                }
            });

        }
    }

    /**
     * Flatten inline models in components
     *
     */
    private void flattenComponents() {
        Map<String, Schema> models = openapi.getComponents().getSchemas();
        if (models == null) {
            return;
        }

        List<String> modelNames = new ArrayList<>(models.keySet());
        for (String modelName : modelNames) {
            Schema schema = models.get(modelName);
            flattenSchema(schema, modelName);
        }
    }

    private void flattenSchema(Schema schema, String path) {
        if (ModelUtils.isModel(schema)) {
            Map<String, Schema> properties = schema.getProperties();
            for (String key : properties.keySet()) {
                Schema property = properties.get(key);
                if (ModelUtils.isModel(property)) {
                    String inlineModelName = resolveModelName(property.getTitle(), path + "_" + key);
                    flattenSchema(property, inlineModelName);

                    openapi.getComponents().addSchemas(inlineModelName, property);
                    Schema refSchema = new Schema().$ref(inlineModelName);
                    schema.addProperties(key, refSchema);
                }
            }
        } else if (ModelUtils.isArraySchema(schema)) {
            ArraySchema arraySchema = (ArraySchema) schema;
            Schema items = arraySchema.getItems();
            if (ModelUtils.isModel(items)) {
                String inlineModelName = resolveModelName(items.getTitle(), path + "_inner");
                flattenSchema(items, inlineModelName);

                openapi.getComponents().addSchemas(inlineModelName, items);
                Schema refSchema = new Schema().$ref(inlineModelName);
                arraySchema.setItems(refSchema);
            }
        }

        if (ModelUtils.isMapSchema(schema)) {
            Schema additionalProperties = ModelUtils.getAdditionalProperties(schema);
            flattenSchema(additionalProperties, path + "_additional");
        }
    }

    /**
     * This function fix models that are string (mostly enum). Before this fix, the
     * example would look something like that in the doc: "\"example from def\""
     *
     * @param m Schema implementation
     */
    private void fixStringModel(Schema m) {
        if (m.getType() != null && m.getType().equals("string") && m.getExample() != null) {
            String example = m.getExample().toString();
            if (example.substring(0, 1).equals("\"") && example.substring(example.length() - 1).equals("\"")) {
                m.setExample(example.substring(1, example.length() - 1));
            }
        }
    }

    private String resolveModelName(String title, String key) {
        if (title == null) {
            return uniqueName(key);
        } else {
            return uniqueName(title);
        }
    }

    private String uniqueName(String key) {
        if (key == null) {
            key = "NULL_UNIQUE_NAME";
            LOGGER.warn("null key found. Default to NULL_UNIQUE_NAME");
        }
        int count = 0;
        boolean done = false;
        key = key.replaceAll("/", "_"); // e.g. /me/videos => _me_videos
        key = key.replaceAll("[^a-z_\\.A-Z0-9 ]", ""); // FIXME: a parameter
        // should not be assigned. Also declare the methods parameters as 'final'.
        while (!done) {
            String name = key;
            if (count > 0) {
                name = key + "_" + count;
            }
            if (openapi.getComponents().getSchemas() == null) {
                return name;
            } else if (!openapi.getComponents().getSchemas().containsKey(name)) {
                return name;
            }
            count += 1;
        }
        return key;
    }

    /**
     * Check this schema is need to be flatten
     *
     * @param schema Schema implementation
     */
    private boolean isNeedInlineSchema(Schema schema) {
        if (schema == null) {
            return false;
        }

        return ModelUtils.isComposedSchema(schema) ||
                (schema.getProperties() != null && schema.getProperties().size() > 1);

    }
}