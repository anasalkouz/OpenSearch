/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.search.suggest.phrase;


import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.ParseFieldMatcher;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.common.lucene.BytesRefs;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentParser.Token;
import org.elasticsearch.index.analysis.ShingleTokenFilterFactory;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.query.QueryParseContext;
import org.elasticsearch.index.query.QueryShardContext;
import org.elasticsearch.script.CompiledScript;
import org.elasticsearch.script.ScriptContext;
import org.elasticsearch.script.Template;
import org.elasticsearch.search.suggest.SuggestUtils;
import org.elasticsearch.search.suggest.SuggestionBuilder;
import org.elasticsearch.search.suggest.SuggestionSearchContext.SuggestionContext;
import org.elasticsearch.search.suggest.phrase.PhraseSuggestionContext.DirectCandidateGenerator;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;

/**
 * Defines the actual suggest command for phrase suggestions ( <tt>phrase</tt>).
 */
public final class PhraseSuggestionBuilder extends SuggestionBuilder<PhraseSuggestionBuilder> {

    private static final String SUGGESTION_NAME = "phrase";

    public static final PhraseSuggestionBuilder PROTOTYPE = new PhraseSuggestionBuilder("_na_");

    protected static final ParseField MAXERRORS_FIELD = new ParseField("max_errors");
    protected static final ParseField RWE_LIKELIHOOD_FIELD = new ParseField("real_word_error_likelihood");
    protected static final ParseField SEPARATOR_FIELD = new ParseField("separator");
    protected static final ParseField CONFIDENCE_FIELD = new ParseField("confidence");
    protected static final ParseField GENERATORS_FIELD = new ParseField("shard_size");
    protected static final ParseField GRAMSIZE_FIELD = new ParseField("gram_size");
    protected static final ParseField SMOOTHING_MODEL_FIELD = new ParseField("smoothing");
    protected static final ParseField FORCE_UNIGRAM_FIELD = new ParseField("force_unigrams");
    protected static final ParseField TOKEN_LIMIT_FIELD = new ParseField("token_limit");
    protected static final ParseField HIGHLIGHT_FIELD = new ParseField("highlight");
    protected static final ParseField PRE_TAG_FIELD = new ParseField("pre_tag");
    protected static final ParseField POST_TAG_FIELD = new ParseField("post_tag");
    protected static final ParseField COLLATE_FIELD = new ParseField("collate");
    protected static final ParseField COLLATE_QUERY_FIELD = new ParseField("query");
    protected static final ParseField COLLATE_QUERY_PARAMS = new ParseField("params");
    protected static final ParseField COLLATE_QUERY_PRUNE = new ParseField("prune");

    private float maxErrors = PhraseSuggestionContext.DEFAULT_MAX_ERRORS;
    private String separator = PhraseSuggestionContext.DEFAULT_SEPARATOR;
    private float realWordErrorLikelihood = PhraseSuggestionContext.DEFAULT_RWE_ERRORLIKELIHOOD;
    private float confidence = PhraseSuggestionContext.DEFAULT_CONFIDENCE;
    // gramSize needs to be optional although there is a default, if unset parser try to detect and use shingle size
    private Integer gramSize;
    private boolean forceUnigrams = PhraseSuggestionContext.DEFAULT_REQUIRE_UNIGRAM;
    private int tokenLimit = NoisyChannelSpellChecker.DEFAULT_TOKEN_LIMIT;
    private String preTag;
    private String postTag;
    private Template collateQuery;
    private Map<String, Object> collateParams;
    private boolean collatePrune = PhraseSuggestionContext.DEFAULT_COLLATE_PRUNE;
    private SmoothingModel model;
    private final Map<String, List<CandidateGenerator>> generators = new HashMap<>();

    public PhraseSuggestionBuilder(String name) {
        super(name);
    }

    /**
     * Sets the gram size for the n-gram model used for this suggester. The
     * default value is <tt>1</tt> corresponding to <tt>unigrams</tt>. Use
     * <tt>2</tt> for <tt>bigrams</tt> and <tt>3</tt> for <tt>trigrams</tt>.
     */
    public PhraseSuggestionBuilder gramSize(int gramSize) {
        if (gramSize < 1) {
            throw new IllegalArgumentException("gramSize must be >= 1");
        }
        this.gramSize = gramSize;
        return this;
    }

    /**
     * get the {@link #gramSize(int)} parameter
     */
    public Integer gramSize() {
        return this.gramSize;
    }

    /**
     * Sets the maximum percentage of the terms that at most considered to be
     * misspellings in order to form a correction. This method accepts a float
     * value in the range [0..1) as a fraction of the actual query terms a
     * number <tt>&gt;=1</tt> as an absolut number of query terms.
     *
     * The default is set to <tt>1.0</tt> which corresponds to that only
     * corrections with at most 1 missspelled term are returned.
     */
    public PhraseSuggestionBuilder maxErrors(float maxErrors) {
        if (maxErrors <= 0.0) {
            throw new IllegalArgumentException("max_error must be > 0.0");
        }
        this.maxErrors = maxErrors;
        return this;
    }

    /**
     * get the maxErrors setting
     */
    public Float maxErrors() {
        return this.maxErrors;
    }

    /**
     * Sets the separator that is used to separate terms in the bigram field. If
     * not set the whitespace character is used as a separator.
     */
    public PhraseSuggestionBuilder separator(String separator) {
        Objects.requireNonNull(separator, "separator cannot be set to null");
        this.separator = separator;
        return this;
    }

    /**
     * get the separator that is used to separate terms in the bigram field.
     */
    public String separator() {
        return this.separator;
    }

    /**
     * Sets the likelihood of a term being a misspelled even if the term exists
     * in the dictionary. The default it <tt>0.95</tt> corresponding to 5% or
     * the real words are misspelled.
     */
    public PhraseSuggestionBuilder realWordErrorLikelihood(float realWordErrorLikelihood) {
        if (realWordErrorLikelihood <= 0.0) {
            throw new IllegalArgumentException("real_word_error_likelihood must be > 0.0");
        }
        this.realWordErrorLikelihood = realWordErrorLikelihood;
        return this;
    }

    /**
     * get the {@link #realWordErrorLikelihood(float)} parameter
     */
    public Float realWordErrorLikelihood() {
        return this.realWordErrorLikelihood;
    }

    /**
     * Sets the confidence level for this suggester. The confidence level
     * defines a factor applied to the input phrases score which is used as a
     * threshold for other suggest candidates. Only candidates that score higher
     * than the threshold will be included in the result. For instance a
     * confidence level of <tt>1.0</tt> will only return suggestions that score
     * higher than the input phrase. If set to <tt>0.0</tt> the top N candidates
     * are returned. The default is <tt>1.0</tt>
     */
    public PhraseSuggestionBuilder confidence(float confidence) {
        if (confidence < 0.0) {
            throw new IllegalArgumentException("confidence must be >= 0.0");
        }
        this.confidence = confidence;
        return this;
    }

    /**
     * get the {@link #confidence()} parameter
     */
    public Float confidence() {
        return this.confidence;
    }

    /**
     * Adds a {@link CandidateGenerator} to this suggester. The
     * {@link CandidateGenerator} is used to draw candidates for each individual
     * phrase term before the candidates are scored.
     */
    public PhraseSuggestionBuilder addCandidateGenerator(CandidateGenerator generator) {
        List<CandidateGenerator> list = this.generators.get(generator.getType());
        if (list == null) {
            list = new ArrayList<>();
            this.generators.put(generator.getType(), list);
        }
        list.add(generator);
        return this;
    }

    /**
     * Clear the candidate generators.
     */
    public PhraseSuggestionBuilder clearCandidateGenerators() {
        this.generators.clear();
        return this;
    }

    /**
     * If set to <code>true</code> the phrase suggester will fail if the analyzer only
     * produces ngrams. the default it <code>true</code>.
     */
    public PhraseSuggestionBuilder forceUnigrams(boolean forceUnigrams) {
        this.forceUnigrams = forceUnigrams;
        return this;
    }

    /**
     * get the setting for {@link #forceUnigrams()}
     */
    public Boolean forceUnigrams() {
        return this.forceUnigrams;
    }

    /**
     * Sets an explicit smoothing model used for this suggester. The default is
     * {@link StupidBackoff}.
     */
    public PhraseSuggestionBuilder smoothingModel(SmoothingModel model) {
        this.model = model;
        return this;
    }

    /**
     * Gets the {@link SmoothingModel}
     */
    public SmoothingModel smoothingModel() {
        return this.model;
    }

    public PhraseSuggestionBuilder tokenLimit(int tokenLimit) {
        if (tokenLimit <= 0) {
            throw new IllegalArgumentException("token_limit must be >= 1");
        }
        this.tokenLimit = tokenLimit;
        return this;
    }

    /**
     * get the {@link #tokenLimit(int)} parameter
     */
    public Integer tokenLimit() {
        return this.tokenLimit;
    }

    /**
     * Setup highlighting for suggestions.  If this is called a highlight field
     * is returned with suggestions wrapping changed tokens with preTag and postTag.
     */
    public PhraseSuggestionBuilder highlight(String preTag, String postTag) {
        if ((preTag == null) != (postTag == null)) {
            throw new IllegalArgumentException("Pre and post tag must both be null or both not be null.");
        }
        this.preTag = preTag;
        this.postTag = postTag;
        return this;
    }

    /**
     * get the pre-tag for the highlighter set with {@link #highlight(String, String)}
     */
    public String preTag() {
        return this.preTag;
    }

    /**
     * get the post-tag for the highlighter set with {@link #highlight(String, String)}
     */
    public String postTag() {
        return this.postTag;
    }

    /**
     * Sets a query used for filtering out suggested phrases (collation).
     */
    public PhraseSuggestionBuilder collateQuery(String collateQuery) {
        this.collateQuery = new Template(collateQuery);
        return this;
    }

    /**
     * Sets a query used for filtering out suggested phrases (collation).
     */
    public PhraseSuggestionBuilder collateQuery(Template collateQueryTemplate) {
        this.collateQuery = collateQueryTemplate;
        return this;
    }

    /**
     * gets the query used for filtering out suggested phrases (collation).
     */
    public Template collateQuery() {
        return this.collateQuery;
    }

    /**
     * Sets additional params for collate script
     */
    public PhraseSuggestionBuilder collateParams(Map<String, Object> collateParams) {
        this.collateParams = collateParams;
        return this;
    }

    /**
     * gets additional params for collate script
     */
    public Map<String, Object> collateParams() {
        return this.collateParams;
    }

    /**
     * Sets whether to prune suggestions after collation
     */
    public PhraseSuggestionBuilder collatePrune(boolean collatePrune) {
        this.collatePrune = collatePrune;
        return this;
    }

    /**
     * Gets whether to prune suggestions after collation
     */
    public Boolean collatePrune() {
        return this.collatePrune;
    }

    @Override
    public XContentBuilder innerToXContent(XContentBuilder builder, Params params) throws IOException {
        builder.field(RWE_LIKELIHOOD_FIELD.getPreferredName(), realWordErrorLikelihood);
        builder.field(CONFIDENCE_FIELD.getPreferredName(), confidence);
        builder.field(SEPARATOR_FIELD.getPreferredName(), separator);
        builder.field(MAXERRORS_FIELD.getPreferredName(), maxErrors);
        if (gramSize != null) {
            builder.field(GRAMSIZE_FIELD.getPreferredName(), gramSize);
        }
        builder.field(FORCE_UNIGRAM_FIELD.getPreferredName(), forceUnigrams);
        builder.field(TOKEN_LIMIT_FIELD.getPreferredName(), tokenLimit);
        if (!generators.isEmpty()) {
            Set<Entry<String, List<CandidateGenerator>>> entrySet = generators.entrySet();
            for (Entry<String, List<CandidateGenerator>> entry : entrySet) {
                builder.startArray(entry.getKey());
                for (CandidateGenerator generator : entry.getValue()) {
                    generator.toXContent(builder, params);
                }
                builder.endArray();
            }
        }
        if (model != null) {
            builder.startObject(SMOOTHING_MODEL_FIELD.getPreferredName());
            model.toXContent(builder, params);
            builder.endObject();
        }
        if (preTag != null) {
            builder.startObject(HIGHLIGHT_FIELD.getPreferredName());
            builder.field(PRE_TAG_FIELD.getPreferredName(), preTag);
            builder.field(POST_TAG_FIELD.getPreferredName(), postTag);
            builder.endObject();
        }
        if (collateQuery != null) {
            builder.startObject(COLLATE_FIELD.getPreferredName());
            builder.field(COLLATE_QUERY_FIELD.getPreferredName(), collateQuery);
            if (collateParams != null) {
                builder.field(COLLATE_QUERY_PARAMS.getPreferredName(), collateParams);
            }
            builder.field(COLLATE_QUERY_PRUNE.getPreferredName(), collatePrune);
            builder.endObject();
        }
        return builder;
    }

    @Override
    protected PhraseSuggestionBuilder innerFromXContent(QueryParseContext parseContext, String suggestionName) throws IOException {
        XContentParser parser = parseContext.parser();
        PhraseSuggestionBuilder suggestion = new PhraseSuggestionBuilder(suggestionName);
        ParseFieldMatcher parseFieldMatcher = parseContext.parseFieldMatcher();
        XContentParser.Token token;
        String fieldName = null;
        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
            if (token == XContentParser.Token.FIELD_NAME) {
                fieldName = parser.currentName();
            } else if (token.isValue()) {
                if (parseFieldMatcher.match(fieldName, SuggestionBuilder.ANALYZER_FIELD)) {
                    suggestion.analyzer(parser.text());
                } else if (parseFieldMatcher.match(fieldName, SuggestionBuilder.FIELDNAME_FIELD)) {
                    suggestion.field(parser.text());
                } else if (parseFieldMatcher.match(fieldName, SuggestionBuilder.SIZE_FIELD)) {
                    suggestion.size(parser.intValue());
                } else if (parseFieldMatcher.match(fieldName, SuggestionBuilder.SHARDSIZE_FIELD)) {
                    suggestion.shardSize(parser.intValue());
                } else if (parseFieldMatcher.match(fieldName, PhraseSuggestionBuilder.RWE_LIKELIHOOD_FIELD)) {
                    suggestion.realWordErrorLikelihood(parser.floatValue());
                } else if (parseFieldMatcher.match(fieldName, PhraseSuggestionBuilder.CONFIDENCE_FIELD)) {
                    suggestion.confidence(parser.floatValue());
                } else if (parseFieldMatcher.match(fieldName, PhraseSuggestionBuilder.SEPARATOR_FIELD)) {
                    suggestion.separator(parser.text());
                } else if (parseFieldMatcher.match(fieldName, PhraseSuggestionBuilder.MAXERRORS_FIELD)) {
                    suggestion.maxErrors(parser.floatValue());
                } else if (parseFieldMatcher.match(fieldName, PhraseSuggestionBuilder.GRAMSIZE_FIELD)) {
                    suggestion.gramSize(parser.intValue());
                } else if (parseFieldMatcher.match(fieldName, PhraseSuggestionBuilder.FORCE_UNIGRAM_FIELD)) {
                    suggestion.forceUnigrams(parser.booleanValue());
                } else if (parseFieldMatcher.match(fieldName, PhraseSuggestionBuilder.TOKEN_LIMIT_FIELD)) {
                    suggestion.tokenLimit(parser.intValue());
                } else {
                    throw new IllegalArgumentException("suggester[phrase] doesn't support field [" + fieldName + "]");
                }
            } else if (token == Token.START_ARRAY) {
                if (parseFieldMatcher.match(fieldName, DirectCandidateGeneratorBuilder.DIRECT_GENERATOR_FIELD)) {
                    // for now we only have a single type of generators
                    while ((token = parser.nextToken()) == Token.START_OBJECT) {
                        suggestion.addCandidateGenerator(DirectCandidateGeneratorBuilder.PROTOTYPE.fromXContent(parseContext));
                    }
                } else {
                    throw new IllegalArgumentException("suggester[phrase]  doesn't support array field [" + fieldName + "]");
                }
            } else if (token == Token.START_OBJECT) {
                if (parseFieldMatcher.match(fieldName, PhraseSuggestionBuilder.SMOOTHING_MODEL_FIELD)) {
                    ensureNoSmoothing(suggestion);
                    suggestion.smoothingModel(SmoothingModel.fromXContent(parseContext));
                } else if (parseFieldMatcher.match(fieldName, PhraseSuggestionBuilder.HIGHLIGHT_FIELD)) {
                    String preTag = null;
                    String postTag = null;
                    while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                        if (token == XContentParser.Token.FIELD_NAME) {
                            fieldName = parser.currentName();
                        } else if (token.isValue()) {
                            if (parseFieldMatcher.match(fieldName, PhraseSuggestionBuilder.PRE_TAG_FIELD)) {
                                preTag = parser.text();
                            } else if (parseFieldMatcher.match(fieldName, PhraseSuggestionBuilder.POST_TAG_FIELD)) {
                                postTag = parser.text();
                            } else {
                                throw new IllegalArgumentException(
                                    "suggester[phrase][highlight] doesn't support field [" + fieldName + "]");
                            }
                        }
                    }
                    suggestion.highlight(preTag, postTag);
                } else if (parseFieldMatcher.match(fieldName, PhraseSuggestionBuilder.COLLATE_FIELD)) {
                    while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                        if (token == XContentParser.Token.FIELD_NAME) {
                            fieldName = parser.currentName();
                        } else if (parseFieldMatcher.match(fieldName, PhraseSuggestionBuilder.COLLATE_QUERY_FIELD)) {
                            if (suggestion.collateQuery() != null) {
                                throw new IllegalArgumentException(
                                        "suggester[phrase][collate] query already set, doesn't support additional [" + fieldName + "]");
                            }
                            Template template = Template.parse(parser, parseFieldMatcher);
                            suggestion.collateQuery(template);
                        } else if (parseFieldMatcher.match(fieldName, PhraseSuggestionBuilder.COLLATE_QUERY_PARAMS)) {
                            suggestion.collateParams(parser.map());
                        } else if (parseFieldMatcher.match(fieldName, PhraseSuggestionBuilder.COLLATE_QUERY_PRUNE)) {
                            if (parser.isBooleanValue()) {
                                suggestion.collatePrune(parser.booleanValue());
                            } else {
                                throw new IllegalArgumentException("suggester[phrase][collate] prune must be either 'true' or 'false'");
                            }
                        } else {
                            throw new IllegalArgumentException(
                                    "suggester[phrase][collate] doesn't support field [" + fieldName + "]");
                        }
                    }
                } else {
                    throw new IllegalArgumentException("suggester[phrase]  doesn't support array field [" + fieldName + "]");
                }
            } else {
                throw new IllegalArgumentException("suggester[phrase] doesn't support field [" + fieldName + "]");
            }
        }
        return suggestion;
    }


    @Override
    public SuggestionContext build(QueryShardContext context) throws IOException {
        PhraseSuggestionContext suggestionContext = new PhraseSuggestionContext(context);
        MapperService mapperService = context.getMapperService();
        populateCommonFields(mapperService, suggestionContext);

        suggestionContext.setSeparator(BytesRefs.toBytesRef(this.separator));
        suggestionContext.setRealWordErrorLikelihood(this.realWordErrorLikelihood);
        suggestionContext.setConfidence(this.confidence);
        suggestionContext.setMaxErrors(this.maxErrors);
        suggestionContext.setSeparator(BytesRefs.toBytesRef(this.separator));
        suggestionContext.setRequireUnigram(this.forceUnigrams);
        suggestionContext.setTokenLimit(this.tokenLimit);
        suggestionContext.setPreTag(BytesRefs.toBytesRef(this.preTag));
        suggestionContext.setPostTag(BytesRefs.toBytesRef(this.postTag));

        if (this.gramSize != null) {
            suggestionContext.setGramSize(this.gramSize);
        }

        for (List<CandidateGenerator> candidateGenerators : this.generators.values()) {
            for (CandidateGenerator candidateGenerator : candidateGenerators) {
                suggestionContext.addGenerator(candidateGenerator.build(mapperService));
            }
        }

        if (this.model != null) {
            suggestionContext.setModel(this.model.buildWordScorerFactory());
        }

        if (this.collateQuery != null) {
            CompiledScript compiledScript = context.getScriptService().compile(this.collateQuery, ScriptContext.Standard.SEARCH,
                    Collections.emptyMap());
            suggestionContext.setCollateQueryScript(compiledScript);
            if (this.collateParams != null) {
                suggestionContext.setCollateScriptParams(this.collateParams);
            }
            suggestionContext.setCollatePrune(this.collatePrune);
        }

        // TODO remove this when field is mandatory in builder ctor
        if (suggestionContext.getField() == null) {
            throw new IllegalArgumentException("The required field option is missing");
        }

        MappedFieldType fieldType = mapperService.fullName(suggestionContext.getField());
        if (fieldType == null) {
            throw new IllegalArgumentException("No mapping found for field [" + suggestionContext.getField() + "]");
        } else if (suggestionContext.getAnalyzer() == null) {
            // no analyzer name passed in, so try the field's analyzer, or the default analyzer
            if (fieldType.searchAnalyzer() == null) {
                suggestionContext.setAnalyzer(mapperService.searchAnalyzer());
            } else {
                suggestionContext.setAnalyzer(fieldType.searchAnalyzer());
            }
        }

        if (suggestionContext.model() == null) {
            suggestionContext.setModel(StupidBackoffScorer.FACTORY);
        }

        if (this.gramSize == null || suggestionContext.generators().isEmpty()) {
            final ShingleTokenFilterFactory.Factory shingleFilterFactory = SuggestUtils
                    .getShingleFilterFactory(suggestionContext.getAnalyzer());
            if (this.gramSize == null) {
                // try to detect the shingle size
                if (shingleFilterFactory != null) {
                    suggestionContext.setGramSize(shingleFilterFactory.getMaxShingleSize());
                    if (suggestionContext.getAnalyzer() == null && shingleFilterFactory.getMinShingleSize() > 1
                            && !shingleFilterFactory.getOutputUnigrams()) {
                        throw new IllegalArgumentException("The default analyzer for field: [" + suggestionContext.getField()
                                + "] doesn't emit unigrams. If this is intentional try to set the analyzer explicitly");
                    }
                }
            }
            if (suggestionContext.generators().isEmpty()) {
                if (shingleFilterFactory != null && shingleFilterFactory.getMinShingleSize() > 1
                        && !shingleFilterFactory.getOutputUnigrams() && suggestionContext.getRequireUnigram()) {
                    throw new IllegalArgumentException("The default candidate generator for phrase suggest can't operate on field: ["
                            + suggestionContext.getField() + "] since it doesn't emit unigrams. "
                            + "If this is intentional try to set the candidate generator field explicitly");
                }
                // use a default generator on the same field
                DirectCandidateGenerator generator = new DirectCandidateGenerator();
                generator.setField(suggestionContext.getField());
                suggestionContext.addGenerator(generator);
            }
        }
        return suggestionContext;
    }

    private static void ensureNoSmoothing(PhraseSuggestionBuilder suggestion) {
        if (suggestion.smoothingModel() != null) {
            throw new IllegalArgumentException("only one smoothing model supported");
        }
    }

    @Override
    public String getWriteableName() {
        return SUGGESTION_NAME;
    }

    @Override
    public void doWriteTo(StreamOutput out) throws IOException {
        out.writeFloat(maxErrors);
        out.writeFloat(realWordErrorLikelihood);
        out.writeFloat(confidence);
        out.writeOptionalVInt(gramSize);
        boolean hasModel = model != null;
        out.writeBoolean(hasModel);
        if (hasModel) {
            out.writePhraseSuggestionSmoothingModel(model);
        }
        out.writeBoolean(forceUnigrams);
        out.writeVInt(tokenLimit);
        out.writeOptionalString(preTag);
        out.writeOptionalString(postTag);
        out.writeString(separator);
        if (collateQuery != null) {
            out.writeBoolean(true);
            collateQuery.writeTo(out);
        } else {
            out.writeBoolean(false);
        }
        out.writeMap(collateParams);
        out.writeOptionalBoolean(collatePrune);
        out.writeVInt(this.generators.size());
        for (Entry<String, List<CandidateGenerator>> entry : this.generators.entrySet()) {
            out.writeString(entry.getKey());
            List<CandidateGenerator> generatorsList = entry.getValue();
            out.writeVInt(generatorsList.size());
            for (CandidateGenerator generator : generatorsList) {
                generator.writeTo(out);
            }
        }
    }

    @Override
    public PhraseSuggestionBuilder doReadFrom(StreamInput in, String name) throws IOException {
        PhraseSuggestionBuilder builder = new PhraseSuggestionBuilder(name);
        builder.maxErrors = in.readFloat();
        builder.realWordErrorLikelihood = in.readFloat();
        builder.confidence = in.readFloat();
        builder.gramSize = in.readOptionalVInt();
        if (in.readBoolean()) {
            builder.model = in.readPhraseSuggestionSmoothingModel();
        }
        builder.forceUnigrams = in.readBoolean();
        builder.tokenLimit = in.readVInt();
        builder.preTag = in.readOptionalString();
        builder.postTag = in.readOptionalString();
        builder.separator = in.readString();
        if (in.readBoolean()) {
            builder.collateQuery = Template.readTemplate(in);
        }
        builder.collateParams = in.readMap();
        builder.collatePrune = in.readOptionalBoolean();
        int generatorsEntries = in.readVInt();
        for (int i = 0; i < generatorsEntries; i++) {
            String type = in.readString();
            int numberOfGenerators = in.readVInt();
            List<CandidateGenerator> generatorsList = new ArrayList<>(numberOfGenerators);
            for (int g = 0; g < numberOfGenerators; g++) {
                DirectCandidateGeneratorBuilder generator = DirectCandidateGeneratorBuilder.PROTOTYPE.readFrom(in);
                generatorsList.add(generator);
            }
            builder.generators.put(type, generatorsList);
        }
        return builder;
    }

    @Override
    protected boolean doEquals(PhraseSuggestionBuilder other) {
        return Objects.equals(maxErrors, other.maxErrors) &&
                Objects.equals(separator, other.separator) &&
                Objects.equals(realWordErrorLikelihood, other.realWordErrorLikelihood) &&
                Objects.equals(confidence, other.confidence) &&
                Objects.equals(generators, other.generators) &&
                Objects.equals(gramSize, other.gramSize) &&
                Objects.equals(model, other.model) &&
                Objects.equals(forceUnigrams, other.forceUnigrams) &&
                Objects.equals(tokenLimit, other.tokenLimit) &&
                Objects.equals(preTag, other.preTag) &&
                Objects.equals(postTag, other.postTag) &&
                Objects.equals(collateQuery, other.collateQuery) &&
                Objects.equals(collateParams, other.collateParams) &&
                Objects.equals(collatePrune, other.collatePrune);
    }

    @Override
    protected int doHashCode() {
        return Objects.hash(maxErrors, separator, realWordErrorLikelihood, confidence,
                generators, gramSize, model, forceUnigrams, tokenLimit, preTag, postTag,
                collateQuery, collateParams, collatePrune);
    }

    /**
     * {@link CandidateGenerator} interface.
     */
    public interface CandidateGenerator extends Writeable<CandidateGenerator>, ToXContent {
        String getType();

        CandidateGenerator fromXContent(QueryParseContext parseContext) throws IOException;

        PhraseSuggestionContext.DirectCandidateGenerator build(MapperService mapperService) throws IOException;
    }
}
