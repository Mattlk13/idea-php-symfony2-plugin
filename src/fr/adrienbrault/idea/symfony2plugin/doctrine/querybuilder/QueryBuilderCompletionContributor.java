package fr.adrienbrault.idea.symfony2plugin.doctrine.querybuilder;

import com.intellij.codeInsight.completion.*;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.CommonProcessors;
import com.intellij.util.ProcessingContext;
import com.jetbrains.php.PhpIcons;
import com.jetbrains.php.lang.psi.PhpPsiUtil;
import com.jetbrains.php.lang.psi.elements.*;
import fr.adrienbrault.idea.symfony2plugin.Symfony2Icons;
import fr.adrienbrault.idea.symfony2plugin.Symfony2ProjectComponent;
import fr.adrienbrault.idea.symfony2plugin.doctrine.querybuilder.dict.QueryBuilderPropertyAlias;
import fr.adrienbrault.idea.symfony2plugin.doctrine.querybuilder.dict.QueryBuilderRelation;
import fr.adrienbrault.idea.symfony2plugin.doctrine.querybuilder.util.MatcherUtil;
import fr.adrienbrault.idea.symfony2plugin.util.MethodMatcher;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public class QueryBuilderCompletionContributor extends CompletionContributor {

    public QueryBuilderCompletionContributor() {

        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), new CompletionProvider<CompletionParameters>() {
            @Override
            protected void addCompletions(@NotNull CompletionParameters completionParameters, ProcessingContext processingContext, @NotNull CompletionResultSet completionResultSet) {

                PsiElement psiElement = completionParameters.getOriginalPosition();
                if (!Symfony2ProjectComponent.isEnabled(psiElement) || !(psiElement.getContext() instanceof StringLiteralExpression)) {
                    return;
                }

                MethodMatcher.MethodMatchParameter methodMatchParameter = new MethodMatcher.StringParameterMatcher(psiElement.getContext(), 0)
                    .withSignature("\\Doctrine\\ORM\\QueryBuilder", "setParameter")
                    .match();

                if(methodMatchParameter == null) {
                    methodMatchParameter = new MethodMatcher.ArrayParameterMatcher(psiElement.getContext(), 0)
                        .withSignature("\\Doctrine\\ORM\\QueryBuilder", "setParameters")
                        .match();
                }

                if(methodMatchParameter == null) {
                    return;
                }

                QueryBuilderMethodReferenceParser qb = getQueryBuilderParser(methodMatchParameter.getMethodReference());
                if(qb == null) {
                    return;
                }

                for(String parameter: qb.collect().getParameters()) {
                    completionResultSet.addElement(LookupElementBuilder.create(parameter));
                }

            }

        });

        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), new CompletionProvider<CompletionParameters>() {
            @Override
            protected void addCompletions(@NotNull CompletionParameters completionParameters, ProcessingContext processingContext, @NotNull CompletionResultSet completionResultSet) {

                PsiElement psiElement = completionParameters.getOriginalPosition();
                if (!Symfony2ProjectComponent.isEnabled(psiElement) || !(psiElement.getContext() instanceof StringLiteralExpression)) {
                    return;
                }

                MethodMatcher.MethodMatchParameter methodMatchParameter = new MethodMatcher.StringParameterMatcher(psiElement.getContext(), 0)
                    .withSignature("\\Doctrine\\ORM\\QueryBuilder", "join")
                    .withSignature("\\Doctrine\\ORM\\QueryBuilder", "leftJoin")
                    .withSignature("\\Doctrine\\ORM\\QueryBuilder", "rightJoin")
                    .withSignature("\\Doctrine\\ORM\\QueryBuilder", "innerJoin")
                    .match();

                if(methodMatchParameter == null) {
                    return;
                }

                QueryBuilderMethodReferenceParser qb = getQueryBuilderParser(methodMatchParameter.getMethodReference());
                if(qb == null) {
                    return;
                }

                QueryBuilderScopeContext collect = qb.collect();
                for(Map.Entry<String, List<QueryBuilderRelation>> parameter: collect.getRelationMap().entrySet()) {
                    for(QueryBuilderRelation relation: parameter.getValue()) {
                        completionResultSet.addElement(LookupElementBuilder.create(parameter.getKey() + "." + relation.getFieldName()).withTypeText(relation.getTargetEntity(), true));
                    }
                }

            }

        });


        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), new CompletionProvider<CompletionParameters>() {
            @Override
            protected void addCompletions(@NotNull CompletionParameters completionParameters, ProcessingContext processingContext, @NotNull CompletionResultSet completionResultSet) {

                PsiElement psiElement = completionParameters.getOriginalPosition();
                if(psiElement == null) {
                    return;
                }

                MethodMatcher.MethodMatchParameter methodMatchParameter = MatcherUtil.matchPropertyField(psiElement.getContext());
                if(methodMatchParameter == null) {
                    return;
                }

                QueryBuilderMethodReferenceParser qb = getQueryBuilderParser(methodMatchParameter.getMethodReference());
                if(qb == null) {
                    return;
                }

                QueryBuilderScopeContext collect = qb.collect();
                for(Map.Entry<String, QueryBuilderPropertyAlias> entry: collect.getPropertyAliasMap().entrySet()) {

                    LookupElementBuilder lookup = LookupElementBuilder.create(entry.getKey());
                    lookup = lookup.withIcon(Symfony2Icons.DOCTRINE);
                    if(entry.getValue().getField() != null) {
                        lookup = lookup.withTypeText(entry.getValue().getField().getTypeName(), true);

                        if(entry.getValue().getField().getRelationType() != null) {
                            lookup = lookup.withTailText(entry.getValue().getField().getRelationType(), true);
                            lookup = lookup.withTypeText(entry.getValue().getField().getRelation(), true);
                            lookup = lookup.withIcon(PhpIcons.CLASS_ICON);
                        }

                    }

                    // highlight fields which are possible in select statement
                    if(collect.getSelects().contains(entry.getValue().getAlias())) {
                        lookup = lookup.withBoldness(true);
                    }

                    completionResultSet.addElement(lookup);

                }

            }

        });

    }

    @Nullable
    public static QueryBuilderMethodReferenceParser getQueryBuilderParser(PsiElement psiElement) {

        final Collection<MethodReference> psiElements = new ArrayList<MethodReference>();

        // QueryBuilder can have nested MethodReferences; get statement context
        Statement statement = PsiTreeUtil.getParentOfType(psiElement, Statement.class);
        if(statement == null) {
            return null;
        }

        // add all methodrefs in current statement scope
        psiElements.addAll(PsiTreeUtil.collectElementsOfType(statement, MethodReference.class));

        // are we in var scope? "$qb->" and var declaration
        Variable variable = PsiTreeUtil.findChildOfAnyType(statement, Variable.class);
        if(variable == null) {
            return new QueryBuilderMethodReferenceParser(psiElement.getProject(), psiElements);
        }

        // resolve non variable declarations so we can search for references
        if(!variable.isDeclaration()) {
            variable = (Variable) variable.resolve();
            if(variable == null) {
                return new QueryBuilderMethodReferenceParser(psiElement.getProject(), psiElements);
            }
        }

        // finally find all querybuilder methodreferences on variable scope
        PhpPsiUtil.hasReferencesInSearchScope(PsiTreeUtil.getParentOfType(variable, Method.class).getUseScope(), variable, new CommonProcessors.FindProcessor<PsiReference>() {
            @Override
            protected boolean accept(PsiReference psiReference) {
                PsiElement variable = psiReference.getElement();

                // get scope of variable statement
                if(variable != null) {
                    // querybuilder call can be nested so find nearest statement and then collect methods refs again
                    Statement statement = PsiTreeUtil.getParentOfType(variable, Statement.class);
                    if(statement != null) {
                        psiElements.addAll(PsiTreeUtil.collectElementsOfType(statement, MethodReference.class));
                    }
                }

                return false;
            }
        });

        return new QueryBuilderMethodReferenceParser(psiElement.getProject(), psiElements);
    }


}