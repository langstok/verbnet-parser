/*
 * Copyright 2019 James Gung
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

package io.github.semlink.app;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.clearwsd.parser.Nlp4jDependencyParser;
import io.github.clearwsd.parser.NlpParser;
import io.github.clearwsd.type.FeatureType;
import io.github.semlink.parser.DefaultVnPredicateDetector;
import io.github.semlink.parser.FilteredPredicateMapper;
import io.github.semlink.parser.LightVerbMapper;
import io.github.semlink.parser.SemanticRoleLabeler;
import io.github.semlink.parser.VerbNetParser;
import io.github.semlink.parser.VerbNetSemParser;
import io.github.semlink.parser.VerbNetSenseClassifier;
import io.github.semlink.parser.VnPredicateDetector;
import io.github.semlink.propbank.type.PropBankArg;
import io.github.semlink.semlink.VerbNetAligner;
import io.github.semlink.verbnet.DefaultVnIndex;
import io.github.semlink.verbnet.VnIndex;

import static io.github.semlink.app.util.JarExtractionUtil.resolveDirectory;
import static io.github.semlink.app.util.JarExtractionUtil.resolveFile;
import static io.github.semlink.parser.VerbNetParser.pbRoleLabeler;

/**
 * Prediction configuration file.
 *
 * @author jgung
 */
@Configuration
public class PredictionConfiguration {

    @Value("${verbnet.demo.pbvn-mappings-path:mappings/pbvn-mappings.json}")
    private String mappingsPath;
    @Value("${verbnet.demo.pb-path:unified-frames.bin}")
    private String pbPath;
    @Value("${verbnet.demo.wsd-mode-path:nlp4j-verbnet-3.3.bin}")
    private String wsdModel;
    @Value("${verbnet.demo.srl-model-path:propbank-srl}")
    private String srlModelDir;
    @Value("${verbnet.demo.lvm-path:mappings/lvm.tsv}")
    private String lvmPath;
    @Value("${verbnet.demo.noun-mappings-path:mappings/nominal-mappings.tsv}")
    private String nounsPath;
    @Value("${verbnet.demo.adjective-mappings-path:mappings/adjectival-mappings.tsv}")
    private String adjectivesPath;
    @Bean
    public VnIndex verbNet() {
        return new DefaultVnIndex();
    }

    @Bean
    public NlpParser nlpParser() {
        return new Nlp4jDependencyParser();
    }

    @Bean
    public VerbNetSenseClassifier verbNetSenseClassifier(@Autowired VnIndex verbNet, @Autowired NlpParser nlpParser) {
        String wsdModel = resolveFile(this.wsdModel);
        return VerbNetSenseClassifier.fromModelPath(wsdModel, verbNet, nlpParser);
    }

    @Bean
    public VerbNetParser verbNetSemanticParser(@Autowired VerbNetSenseClassifier verbNetSenseClassifier,
                                               @Autowired VnIndex verbNet) {
        String mappingsPath = resolveFile(this.mappingsPath);
        String modelDir = resolveDirectory(this.srlModelDir);
        String lvmPath = resolveFile(this.lvmPath);
        String pbPath = resolveFile(this.pbPath);
        String nounsPath = resolveFile(this.nounsPath);
        String adjPath = resolveFile(this.adjectivesPath);

        SemanticRoleLabeler<PropBankArg> roleLabeler = pbRoleLabeler(modelDir);

        VerbNetAligner aligner = VerbNetAligner.of(mappingsPath, pbPath);

        LightVerbMapper mapper = LightVerbMapper.fromMappingsPath(lvmPath, verbNet);
        FilteredPredicateMapper nominalMapper = FilteredPredicateMapper.fromMappingsPath(nounsPath, verbNet,
                child -> child.feature(FeatureType.Pos).toString().toUpperCase().startsWith("N")
                        && !child.feature(FeatureType.Dep).toString().toUpperCase().equals("COMPOUND"));


        FilteredPredicateMapper adjectivalMapper = FilteredPredicateMapper.fromMappingsPath(adjPath, verbNet,
                child -> child.feature(FeatureType.Pos).toString().toUpperCase().startsWith("JJ"));
        VnPredicateDetector predicateDetector = new DefaultVnPredicateDetector(verbNetSenseClassifier, mapper,
                nominalMapper,
                adjectivalMapper);

        VerbNetSemParser parser = new VerbNetSemParser(roleLabeler, aligner);

        return new VerbNetParser(predicateDetector, verbNetSenseClassifier, parser);
    }

}
