/* Copyright (C) 2010 SpringSource
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.grails.datastore.mapping.mongo.config;

import com.mongodb.MongoClientURI;
import groovy.lang.Closure;

import java.beans.PropertyDescriptor;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.Binary;
import org.bson.types.Code;
import org.bson.types.ObjectId;
import org.bson.types.Symbol;
import org.grails.datastore.bson.codecs.CodecExtensions;
import org.grails.datastore.gorm.mongo.geo.*;
import org.grails.datastore.gorm.mongo.simple.EnumType;
import org.grails.datastore.mapping.config.AbstractGormMappingFactory;
import org.grails.datastore.mapping.document.config.Attribute;
import org.grails.datastore.mapping.document.config.Collection;
import org.grails.datastore.mapping.document.config.DocumentMappingContext;
import org.grails.datastore.mapping.model.AbstractClassMapping;
import org.grails.datastore.mapping.model.ClassMapping;
import org.grails.datastore.mapping.model.EmbeddedPersistentEntity;
import org.grails.datastore.mapping.model.MappingContext;
import org.grails.datastore.mapping.model.MappingFactory;
import org.grails.datastore.mapping.model.PersistentEntity;

import org.grails.datastore.mapping.model.types.Identity;
import org.grails.datastore.mapping.mongo.MongoConstants;
import org.grails.datastore.mapping.mongo.MongoDatastore;
import org.grails.datastore.mapping.mongo.connections.AbstractMongoConnectionSourceSettings;
import org.grails.datastore.mapping.mongo.connections.MongoConnectionSourceSettings;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.converter.ConverterRegistry;
import org.springframework.core.env.PropertyResolver;

/**
 * Models a {@link org.grails.datastore.mapping.model.MappingContext} for Mongo.
 *
 * @author Graeme Rocher
 */
@SuppressWarnings("rawtypes")
public class MongoMappingContext extends DocumentMappingContext {
    /**
     * Java types supported as mongo property types.
     */
    private static final Set<String> MONGO_NATIVE_TYPES = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            Double.class.getName(),
            String.class.getName(),
            Document.class.getName(),
            "com.mongodb.DBObject",
            org.bson.types.Binary.class.getName(),
            org.bson.types.ObjectId.class.getName(),
            "com.mongodb.DBRef",
            Boolean.class.getName(),
            Date.class.getName(),
            Pattern.class.getName(),
            Symbol.class.getName(),
            Integer.class.getName(),
            "org.bson.types.BSONTimestamp",
            Code.class.getName(),
            "org.bson.types.CodeWScope",
            Long.class.getName(),
            UUID.class.getName(),
            byte[].class.getName(),
            Byte.class.getName()
    )));

    public MongoMappingContext(String defaultDatabaseName) {
        this(defaultDatabaseName, null);
    }

    public MongoMappingContext(String defaultDatabaseName, Closure defaultMapping) {
        this(defaultDatabaseName, defaultMapping, new Class[0]);
    }

    /**
     * Constructs a new {@link MongoMappingContext} for the given arguments
     *
     * @param defaultDatabaseName The default database name
     * @param defaultMapping The default database mapping configuration
     * @param classes The persistent classes
     */
    public MongoMappingContext(String defaultDatabaseName, Closure defaultMapping, Class...classes) {
        super(defaultDatabaseName, defaultMapping);
        initialize(classes);

    }

    /**
     * Constructs a new {@link MongoMappingContext} for the given arguments
     *
     * @param configuration The configuration
     * @param classes The persistent classes
     * @deprecated  Use {@link #MongoMappingContext(AbstractMongoConnectionSourceSettings, Class[])} instead
     *
     */
    @Deprecated
    public MongoMappingContext(PropertyResolver configuration, Class...classes) {
        this(getDefaultDatabaseName(configuration), configuration.getProperty(MongoSettings.SETTING_DEFAULT_MAPPING, Closure.class, null), classes);
    }

    /**
     * Construct a new context for the given settings and classes
     *
     * @param settings The settings
     * @param classes The classes
     */
    public MongoMappingContext(AbstractMongoConnectionSourceSettings settings, Class... classes) {
        super(settings.getDatabase(), settings);
        initialize(classes);
    }

    private void initialize(Class[] classes) {
        registerMongoTypes();
        final ConverterRegistry converterRegistry = getConverterRegistry();
        converterRegistry.addConverter(new Converter<String, ObjectId>() {
            public ObjectId convert(String source) {
                return new ObjectId(source);
            }
        });

        converterRegistry.addConverter(new Converter<ObjectId, String>() {
            public String convert(ObjectId source) {
                return source.toString();
            }
        });

        converterRegistry.addConverter(new Converter<byte[], Binary>() {
            public Binary convert(byte[] source) {
                return new Binary(source);
            }
        });

        converterRegistry.addConverter(new Converter<Binary, byte[]>() {
            public byte[] convert(Binary source) {
                return source.getData();
            }
        });

        for (Converter converter : CodecExtensions.getBsonConverters()) {
            converterRegistry.addConverter(converter);
        }

        addPersistentEntities(classes);
    }

    /**
     * Check whether a type is a native mongo type that can be stored by the mongo driver without conversion.
     * @param clazz The class to check.
     * @return true if no conversion is required and the type can be stored natively.
     */
    public static boolean isMongoNativeType(Class clazz) {
        return MongoMappingContext.MONGO_NATIVE_TYPES.contains(clazz.getName()) ||
                Bson.class.isAssignableFrom(clazz.getClass()) ;
    }

    public static String getDefaultDatabaseName(PropertyResolver configuration) {
        String connectionString = configuration.getProperty(MongoDatastore.SETTING_CONNECTION_STRING, String.class, null);

        if(connectionString != null) {
            MongoClientURI mongoClientURI = new MongoClientURI(connectionString);
            String database = mongoClientURI.getDatabase();
            if(database != null) {
                return database;
            }
        }
        return configuration.getProperty(MongoSettings.SETTING_DATABASE_NAME, "test");
    }

    private final class MongoDocumentMappingFactory extends
            AbstractGormMappingFactory<MongoCollection, MongoAttribute> {
        @Override
        protected Class<MongoAttribute> getPropertyMappedFormType() {
            return MongoAttribute.class;
        }

        @Override
        protected Class<MongoCollection> getEntityMappedFormType() {
            return MongoCollection.class;
        }


        @Override
        public Identity<MongoAttribute> createIdentity(PersistentEntity owner, MappingContext context, PropertyDescriptor pd) {
            Identity<MongoAttribute> identity = super.createIdentity(owner, context, pd);
            identity.getMapping().getMappedForm().setTargetName(MongoConstants.MONGO_ID_FIELD);
            return identity;
        }

        @Override
        public boolean isSimpleType(Class propType) {
            if (propType == null) return false;
            if (propType.isArray()) {
                return isSimpleType(propType.getComponentType()) || super.isSimpleType(propType);
            }
            return isMongoNativeType(propType) || super.isSimpleType(propType);
        }
    }



    protected void registerMongoTypes() {
        MappingFactory<Collection, Attribute> mappingFactory = getMappingFactory();
        mappingFactory.registerCustomType(new GeometryCollectionType());
        mappingFactory.registerCustomType(new PointType());
        mappingFactory.registerCustomType(new PolygonType());
        mappingFactory.registerCustomType(new LineStringType());
        mappingFactory.registerCustomType(new MultiLineStringType());
        mappingFactory.registerCustomType(new MultiPointType());
        mappingFactory.registerCustomType(new MultiPolygonType());
        mappingFactory.registerCustomType(new ShapeType());
        mappingFactory.registerCustomType(new BoxType());
        mappingFactory.registerCustomType(new CircleType());
        mappingFactory.registerCustomType(new EnumType());
    }

    @Override
    protected MappingFactory createDocumentMappingFactory(Closure defaultMapping) {
        MongoDocumentMappingFactory mongoDocumentMappingFactory = new MongoDocumentMappingFactory();
        mongoDocumentMappingFactory.setDefaultMapping(defaultMapping);
        return mongoDocumentMappingFactory;
    }

    @Override
    public PersistentEntity createEmbeddedEntity(Class type) {
        return new DocumentEmbeddedPersistentEntity(type, this);
    }

    class DocumentEmbeddedPersistentEntity extends EmbeddedPersistentEntity {

        private DocumentCollectionMapping classMapping ;
        public DocumentEmbeddedPersistentEntity(Class type, MappingContext ctx) {
            super(type, ctx);
            classMapping = new DocumentCollectionMapping(this, ctx);
        }

        @Override
        public ClassMapping getMapping() {
            return classMapping;
        }
        public class DocumentCollectionMapping extends AbstractClassMapping<Collection> {
            private Collection mappedForm;

            public DocumentCollectionMapping(PersistentEntity entity, MappingContext context) {
                super(entity, context);
                this.mappedForm = (Collection) context.getMappingFactory().createMappedForm(DocumentEmbeddedPersistentEntity.this);
            }
            @Override
            public Collection getMappedForm() {
                return mappedForm ;
            }
        }
    }
}
