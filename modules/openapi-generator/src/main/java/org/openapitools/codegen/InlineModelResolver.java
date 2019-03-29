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

import io.swagger.v3.core.util.Json;
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
    private Map<String, Schema> addedModels = new HashMap<String, Schema>();
    private Map<String, String> generatedSignature = new HashMap<String, String>();
    static Logger LOGGER = LoggerFactory.getLogger(InlineModelResolver.class);

    void flatten(OpenAPI openapi) {
        this.openapi = openapi;

        if (openapi.getComponents() == null) {
            openapi.setComponents(new Components());
        }

        if (openapi.getComponents().getSchemas() == null) {
            openapi.getComponents().setSchemas(new HashMap<String, Schema>());
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
                flattenRequestBody(openapi, operation, requestBody);
                flattenParameters(operation);
                flattenResponses(openapi, pathname, operation);
            }
        }
    }

    /**
     * Flatten inline models in RequestBody
     *
     * @param openAPI target spec
     * @param operation target operation
     * @param requestBody target requestBody

     */
    private void flattenRequestBody(OpenAPI openAPI, Operation operation, RequestBody requestBody) {
        if (requestBody == null) {
            return;
        }

        Schema requestBodySchema = ModelUtils.getSchemaFromRequestBody(requestBody);
        String requestBodySchemaName = resolveModelName(requestBodySchema.getTitle(), operation.getOperationId());
        flattenSchema(requestBodySchema, requestBodySchemaName);

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
            String parameterModelName = resolveModelName(parameterModel.getTitle(),
                    parameterModel.getName() + parameter.getName());
            flattenSchema(parameterModel, parameterModelName);
        }
    }

    /**
     * Flatten inline models in ApiResponses
     *
     * @param openAPI target spec
     * @param pathname target pathname
     * @param operation target operation
     */
    private void flattenResponses(OpenAPI openAPI, String pathname, Operation operation) {
        ApiResponses responses = operation.getResponses();
        if (responses == null) {
            return;
        }

        for (String key : responses.keySet()) {
            ApiResponse response = responses.get(key);
            if (ModelUtils.getSchemaFromResponse(response) == null) {
                continue;
            }

            Schema property = ModelUtils.getSchemaFromResponse(response);
            if (property instanceof ObjectSchema) {
                ObjectSchema op = (ObjectSchema) property;
                if (op.getProperties() != null && op.getProperties().size() > 0) {
                    String modelName = resolveModelName(op.getTitle(), "inline_response_" + key);
                    Schema model = modelFromProperty(op, modelName);
                    String existing = matchGenerated(model);
                    Content content = response.getContent();
                    for (MediaType mediaType : content.values()) {
                        if (existing != null) {
                            Schema schema = this.makeSchema(existing, property);
                            schema.setRequired(op.getRequired());
                            mediaType.setSchema(schema);
                        } else {
                            Schema schema = this.makeSchema(modelName, property);
                            schema.setRequired(op.getRequired());
                            mediaType.setSchema(schema);
                            addGenerated(modelName, model);
                            openAPI.getComponents().addSchemas(modelName, model);
                        }
                    }
                }
            } else if (property instanceof ArraySchema) {
                ArraySchema ap = (ArraySchema) property;
                Schema inner = ap.getItems();
                if (inner instanceof ObjectSchema) {
                    ObjectSchema op = (ObjectSchema) inner;
                    if (op.getProperties() != null && op.getProperties().size() > 0) {
                        flattenProperties(op.getProperties(), pathname);
                        String modelName = resolveModelName(op.getTitle(),
                                "inline_response_" + key);
                        Schema innerModel = modelFromProperty(op, modelName);
                        String existing = matchGenerated(innerModel);
                        if (existing != null) {
                            Schema schema = this.makeSchema(existing, op);
                            schema.setRequired(op.getRequired());
                            ap.setItems(schema);
                        } else {
                            Schema schema = this.makeSchema(modelName, op);
                            schema.setRequired(op.getRequired());
                            ap.setItems(schema);
                            addGenerated(modelName, innerModel);
                            openAPI.getComponents().addSchemas(modelName, innerModel);
                        }
                    }
                }
            } else if (property instanceof MapSchema) {
                MapSchema mp = (MapSchema) property;
                Schema innerProperty = ModelUtils.getAdditionalProperties(mp);
                if (innerProperty instanceof ObjectSchema) {
                    ObjectSchema op = (ObjectSchema) innerProperty;
                    if (op.getProperties() != null && op.getProperties().size() > 0) {
                        flattenProperties(op.getProperties(), pathname);
                        String modelName = resolveModelName(op.getTitle(),
                                "inline_response_" + key);
                        Schema innerModel = modelFromProperty(op, modelName);
                        String existing = matchGenerated(innerModel);
                        if (existing != null) {
                            Schema schema = new Schema().$ref(existing);
                            schema.setRequired(op.getRequired());
                            mp.setAdditionalProperties(schema);
                        } else {
                            Schema schema = new Schema().$ref(modelName);
                            schema.setRequired(op.getRequired());
                            mp.setAdditionalProperties(schema);
                            addGenerated(modelName, innerModel);
                            openAPI.getComponents().addSchemas(modelName, innerModel);
                        }
                    }
                }
            }
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

    private String matchGenerated(Schema model) {
        String json = Json.pretty(model);
        if (generatedSignature.containsKey(json)) {
            return generatedSignature.get(json);
        }
        return null;
    }

    private void addGenerated(String name, Schema model) {
        generatedSignature.put(Json.pretty(model), name);
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

    private void flattenProperties(Map<String, Schema> properties, String path) {
        if (properties == null) {
            return;
        }
        Map<String, Schema> propsToUpdate = new HashMap<String, Schema>();
        Map<String, Schema> modelsToAdd = new HashMap<String, Schema>();
        for (String key : properties.keySet()) {
            Schema property = properties.get(key);
            if (property instanceof ObjectSchema && ((ObjectSchema) property).getProperties() != null
                    && ((ObjectSchema) property).getProperties().size() > 0) {
                ObjectSchema op = (ObjectSchema) property;
                String modelName = resolveModelName(op.getTitle(), path + "_" + key);
                Schema model = modelFromProperty(op, modelName);
                String existing = matchGenerated(model);
                if (existing != null) {
                    Schema schema = new Schema().$ref(existing);
                    schema.setRequired(op.getRequired());
                    propsToUpdate.put(key, schema);
                } else {
                    Schema schema = new Schema().$ref(modelName);
                    schema.setRequired(op.getRequired());
                    propsToUpdate.put(key, schema);
                    modelsToAdd.put(modelName, model);
                    addGenerated(modelName, model);
                    openapi.getComponents().addSchemas(modelName, model);
                }
            } else if (property instanceof ArraySchema) {
                ArraySchema ap = (ArraySchema) property;
                Schema inner = ap.getItems();
                if (inner instanceof ObjectSchema) {
                    ObjectSchema op = (ObjectSchema) inner;
                    if (op.getProperties() != null && op.getProperties().size() > 0) {
                        flattenProperties(op.getProperties(), path);
                        String modelName = resolveModelName(op.getTitle(), path + "_" + key);
                        Schema innerModel = modelFromProperty(op, modelName);
                        String existing = matchGenerated(innerModel);
                        if (existing != null) {
                            Schema schema = new Schema().$ref(existing);
                            schema.setRequired(op.getRequired());
                            ap.setItems(schema);
                        } else {
                            Schema schema = new Schema().$ref(modelName);
                            schema.setRequired(op.getRequired());
                            ap.setItems(schema);
                            addGenerated(modelName, innerModel);
                            openapi.getComponents().addSchemas(modelName, innerModel);
                        }
                    }
                }
            }
            if (ModelUtils.isMapSchema(property)) {
                Schema inner = ModelUtils.getAdditionalProperties(property);
                if (inner instanceof ObjectSchema) {
                    ObjectSchema op = (ObjectSchema) inner;
                    if (op.getProperties() != null && op.getProperties().size() > 0) {
                        flattenProperties(op.getProperties(), path);
                        String modelName = resolveModelName(op.getTitle(), path + "_" + key);
                        Schema innerModel = modelFromProperty(op, modelName);
                        String existing = matchGenerated(innerModel);
                        if (existing != null) {
                            Schema schema = new Schema().$ref(existing);
                            schema.setRequired(op.getRequired());
                            property.setAdditionalProperties(schema);
                        } else {
                            Schema schema = new Schema().$ref(modelName);
                            schema.setRequired(op.getRequired());
                            property.setAdditionalProperties(schema);
                            addGenerated(modelName, innerModel);
                            openapi.getComponents().addSchemas(modelName, innerModel);
                        }
                    }
                }
            }
        }
        if (propsToUpdate.size() > 0) {
            for (String key : propsToUpdate.keySet()) {
                properties.put(key, propsToUpdate.get(key));
            }
        }
        for (String key : modelsToAdd.keySet()) {
            openapi.getComponents().addSchemas(key, modelsToAdd.get(key));
            this.addedModels.put(key, modelsToAdd.get(key));
        }
    }

    private Schema modelFromProperty(ObjectSchema object, String path) {
        String description = object.getDescription();
        String example = null;
        Object obj = object.getExample();
        if (obj != null) {
            example = obj.toString();
        }
        XML xml = object.getXml();
        Map<String, Schema> properties = object.getProperties();
        Schema model = new Schema();
        model.setDescription(description);
        model.setExample(example);
        model.setName(object.getName());
        model.setXml(xml);
        model.setRequired(object.getRequired());
        model.setNullable(object.getNullable());
        if (properties != null) {
            flattenProperties(properties, path);
            model.setProperties(properties);
        }
        return model;
    }

    /**
     * Make a Schema
     *
     * @param ref      new property name
     * @param property Schema
     * @return {@link Schema} A constructed OpenAPI property
     */
    private Schema makeSchema(String ref, Schema property) {
        Schema newProperty = new Schema().$ref(ref);
        this.copyVendorExtensions(property, newProperty);
        return newProperty;
    }

    /**
     * Copy vendor extensions from Model to another Model
     *
     * @param source source property
     * @param target target property
     */

    private void copyVendorExtensions(Schema source, Schema target) {
        Map<String, Object> vendorExtensions = source.getExtensions();
        if (vendorExtensions == null) {
             return;
        }
        for (String extName : vendorExtensions.keySet()) {
            target.addExtension(extName, vendorExtensions.get(extName));
        }
    }
}