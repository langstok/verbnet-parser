package io.github.semlink.parser;

import com.google.common.base.Preconditions;
import io.github.clearwsd.SensePrediction;
import io.github.clearwsd.type.DepTree;
import io.github.clearwsd.type.FeatureType;
import io.github.clearwsd.verbnet.DefaultVnIndex;
import io.github.clearwsd.verbnet.VnClass;
import io.github.clearwsd.verbnet.VnIndex;
import io.github.clearwsd.verbnet.VnMember;
import io.github.semlink.app.DefaultChunking;
import io.github.semlink.app.Span;
import io.github.semlink.propbank.type.ArgNumber;
import io.github.semlink.propbank.type.PropBankArg;
import io.github.semlink.semlink.PropBankPhrase;
import io.github.semlink.semlink.PropBankVerbNetAligner;
import io.github.semlink.semlink.aligner.PbVnAlignment;
import io.github.semlink.verbnet.semantics.EventArgument;
import io.github.semlink.verbnet.semantics.SemanticPredicate;
import io.github.semlink.verbnet.semantics.ThematicRoleArgument;
import io.github.semlink.verbnet.semantics.VerbSpecificArgument;
import io.github.semlink.verbnet.type.FramePhrase;
import io.github.semlink.verbnet.type.NounPhrase;
import io.github.semlink.verbnet.type.SemanticArgumentType;
import io.github.semlink.verbnet.type.SemanticPredicateType;
import io.github.semlink.verbnet.type.ThematicRoleType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.experimental.Delegate;
import lombok.extern.slf4j.Slf4j;

/**
 * VerbNet semantic parser.
 *
 * @author jgung
 */
@Slf4j
@AllArgsConstructor
public class VerbNetSemanticParser implements SemanticRoleLabeler<PropBankArg> {

    private VerbNetSenseClassifier classifier;
    @Delegate
    private DefaultSemanticRoleLabeler<PropBankArg> roleLabeler;

    private PropBankVerbNetAligner aligner;
    private PropBankLightVerbMapper lightVerbMapper;

    public VerbNetSemanticParser(@NonNull DefaultSemanticRoleLabeler<PropBankArg> roleLabeler,
        @NonNull VerbNetSenseClassifier classifier,
        @NonNull PropBankVerbNetAligner aligner) {
        this.roleLabeler = roleLabeler;
        this.classifier = classifier;
        this.aligner = aligner;
        this.lightVerbMapper = new PropBankLightVerbMapper(new HashMap<>(), roleLabeler);
    }

    private List<SemanticPredicate> parsePredicates(@NonNull PbVnAlignment alignment, @NonNull DepTree parsed) {
        List<SemanticPredicate> predicates = alignment.frame().frame().predicates().stream()
            .map(SemanticPredicate::of)
            .collect(Collectors.toList());

        Map<ThematicRoleType, ThematicRoleType> equalsRoles = new HashMap<>();
        for (SemanticPredicate predicate : predicates) {
            if (predicate.type() == SemanticPredicateType.EQUALS) {
                List<ThematicRoleArgument<PropBankPhrase>> args = predicate.get(SemanticArgumentType.THEMROLE);
                if (args.size() == 2) {
                    equalsRoles.put(args.get(0).thematicRoleType(), args.get(1).thematicRoleType());
                    equalsRoles.put(args.get(1).thematicRoleType(), args.get(0).thematicRoleType());
                }
            }
        }

        List<SemanticPredicate> filtered = new ArrayList<>();
        for (SemanticPredicate predicate : predicates) {
            if (predicate.type() == SemanticPredicateType.EQUALS) {
                continue;
            }
            List<EventArgument<VnClass>> args = predicate.get(SemanticArgumentType.EVENT);
            for (EventArgument<VnClass> arg : args) {
                arg.variable(alignment.proposition().predicate().sense());
            }

            List<ThematicRoleArgument<PropBankPhrase>> roles = predicate.get(SemanticArgumentType.THEMROLE);
            for (ThematicRoleArgument<PropBankPhrase> role : roles) {
                Optional<FramePhrase> phrase = alignment.byRole(role.thematicRoleType());
                phrase.ifPresent(framePhrase -> role.variable(alignment.alignment().getSource(framePhrase)));
                if (!phrase.isPresent() && equalsRoles.containsKey(role.thematicRoleType())) {
                    ThematicRoleType equivalentRole = equalsRoles.get(role.thematicRoleType());
                    phrase = alignment.byRole(equivalentRole);
                    phrase.ifPresent(framePhrase -> role.variable(alignment.alignment().getSource(framePhrase)));
                }
            }
            filtered.add(predicate);

            VnClass sense = alignment.proposition().predicate().sense();
            if (null == sense) {
                continue;
            }
            String lemma = parsed.get(alignment.proposition().predicate().index()).feature(FeatureType.Lemma);
            List<String> features = sense.members().stream()
                .filter(member -> member.name().equals(lemma))
                .map(VnMember::features)
                .findFirst().orElse(Collections.emptyList());
            if (features.isEmpty()) {
                continue;
            }
            List<VerbSpecificArgument<String>> vsa = predicate.get(SemanticArgumentType.VERBSPECIFIC);
            for (VerbSpecificArgument<String> role : vsa) {
                role.variable(String.join(", ", features));
            }
        }

        return filtered;
    }

    public VerbNetSemanticParse parseSentence(@NonNull String sentence) {
        final List<String> tokens = classifier.tokenize(sentence);
        final DepTree parsed = classifier.parse(tokens);
        final VerbNetSemanticParse parse = new VerbNetSemanticParse()
            .tokens(tokens)
            .tree(parsed);

        List<SensePrediction<VnClass>> senses = classifier.predict(parsed);
        List<Proposition<VnClass, PropBankArg>> props = roleLabeler.parse(parsed, senses).stream()
            .map(prop -> lightVerbMapper.mapProp(parsed, prop).orElse(prop))
            .collect(Collectors.toList());

        for (Proposition<VnClass, PropBankArg> prop : props) {
            if (prop.predicate().sense() == null) {
                continue;
            }

            VerbNetProp vnProp = new VerbNetProp()
                .propbankProp(prop)
                .tokens(tokens);

            aligner.align(prop, parsed).ifPresent(pbVnAlignment -> {
                // get thematic role alignment
                Preconditions.checkState(pbVnAlignment.sourcePhrases().size() == prop.arguments().spans().size());
                List<Span<ThematicRoleType>> types = new ArrayList<>();
                Iterator<PropBankPhrase> propBankPhrases = pbVnAlignment.sourcePhrases().iterator();
                for (Span<PropBankArg> span : prop.arguments().spans()) {
                    PropBankPhrase phrase = propBankPhrases.next();

                    if (phrase.getNumber() == ArgNumber.V) {
                        types.add(Span.convert(span, ThematicRoleType.VERB));
                    } else {
                        Optional<NounPhrase> nounPhrase = pbVnAlignment.alignedPhrases(phrase).stream()
                            .filter(np -> np instanceof NounPhrase)
                            .map(np -> (NounPhrase) np)
                            .findFirst();
                        nounPhrase.ifPresent(np -> types.add(Span.convert(span, np.thematicRoleType())));
                    }

                }
                vnProp.verbnetProp(new Proposition<>(prop.predicate(), new DefaultChunking<>(types)));
                // get semantic predicates
                vnProp.predicates(parsePredicates(pbVnAlignment, parsed));
            });

            parse.props().add(vnProp);
        }

        return parse;
    }

    public List<VerbNetSemanticParse> parseDocument(@NonNull String document) {
        return classifier.segment(document).stream().map(this::parseSentence).collect(Collectors.toList());
    }

    public static DefaultSemanticRoleLabeler<PropBankArg> roleLabeler(@NonNull String modelPath) {
        return new DefaultSemanticRoleLabeler<>(RoleLabelerUtils.shallowSemanticParser(modelPath), PropBankArg::fromLabel);
    }

    public static void main(String[] args) {
        String mappingsPath = "data/pbvn-mappings.json.updated.json";
        String modelDir = "data/models/unified-propbank";
        String wsdModel = "data/models/verbnet/nlp4j-verbnet-3.3.bin";
        String lightVerbMappings = "semparse-core/src/main/resources/lvm.tsv";

        DefaultSemanticRoleLabeler<PropBankArg> roleLabeler = roleLabeler(modelDir);
        VnIndex verbNet = new DefaultVnIndex();
        VerbNetSenseClassifier classifier = VerbNetSenseClassifier.fromModelPath(wsdModel, verbNet);
        PropBankVerbNetAligner aligner = PropBankVerbNetAligner.of(mappingsPath);
        VerbNetSemanticParser parser = new VerbNetSemanticParser(classifier, roleLabeler, aligner,
            new PropBankLightVerbMapper(PropBankLightVerbMapper.fromMappingsPath(lightVerbMappings, verbNet),
                roleLabeler));

        Scanner scanner = new Scanner(System.in);
        while (true) {
            try {
                System.out.print(">> ");
                String line = scanner.nextLine().trim();
                if (line.equalsIgnoreCase("quit")) {
                    break;
                }
                parser.parseDocument(line)
                    .forEach(System.out::println);
            } catch (Exception e) {
                log.warn("An unexpected error occurred", e);
            }
        }
    }

}