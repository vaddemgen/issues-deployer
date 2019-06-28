package com.vaddemgen.issuesdeployer.base.datamapperlayer;

import static com.vaddemgen.issuesdeployer.base.datamapperlayer.factory.IssueEntityFactory.createIssueEntity;
import static com.vaddemgen.issuesdeployer.base.datamapperlayer.factory.IssueFactory.createIssue;
import static com.vaddemgen.issuesdeployer.base.datamapperlayer.group.util.IssueEntityUtil.mergeIssueEntity;
import static java.util.stream.Collectors.partitioningBy;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

import com.vaddemgen.issuesdeployer.base.businesslayer.model.Issue;
import com.vaddemgen.issuesdeployer.base.businesslayer.model.Project;
import com.vaddemgen.issuesdeployer.base.businesslayer.model.gitaccount.GitAccount;
import com.vaddemgen.issuesdeployer.base.datamapperlayer.factory.IssueFactory;
import com.vaddemgen.issuesdeployer.base.datamapperlayer.jparepository.IssueRepository;
import com.vaddemgen.issuesdeployer.base.datamapperlayer.jparepository.ProjectRepository;
import com.vaddemgen.issuesdeployer.base.datamapperlayer.orm.IssueEntity;
import com.vaddemgen.issuesdeployer.base.datamapperlayer.orm.ProjectEntity;
import com.vaddemgen.issuesdeployer.base.gitclient.datamapperlayer.GitClientFactory;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import lombok.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class IssueDataMapperImpl implements IssueDataMapper {

  private final GitClientFactory gitClientFactory;
  private final IssueRepository issueRepository;
  private final ProjectRepository projectRepository;

  // Self autowired
  private IssueDataMapperImpl self;

  public IssueDataMapperImpl(
      GitClientFactory gitClientFactory,
      IssueRepository issueRepository,
      ProjectRepository projectRepository
  ) {
    this.gitClientFactory = gitClientFactory;
    this.issueRepository = issueRepository;
    this.projectRepository = projectRepository;
  }

  @Autowired
  public void setSelf(IssueDataMapperImpl self) {
    this.self = self;
  }

  @Override
  public Stream<Issue> findIssuesByProject(GitAccount gitAccount, Project project) {

    var issues = issueRepository
        .findAllByProjectId(project.getId());

    if (!issues.isEmpty()) {
      return issues.stream()
          .map(IssueFactory::createIssue);
    }

    var client = gitClientFactory.createGitClient(gitAccount);

    try {
      List<Issue> loadedIssues = client.findIssues(project).collect(toList());
      if (!loadedIssues.isEmpty()) {
        return self.saveIssues(project, loadedIssues);
      }
    } catch (IOException | InterruptedException e) { // TODO: handle the exception
      e.printStackTrace();
    }

    return Stream.empty();
  }

  @Override
  @Transactional
  public Stream<Issue> saveIssues(Project project, Collection<Issue> issues) {
    return createIssues(
        issues,
        projectRepository.findOneForShare(project.getId())
            .orElseThrow() // TODO: Refactor to ModelNotFoundException
    );
  }

  @Transactional
  @Override
  public Stream<Issue> mergeIssues(
      @NonNull Project issuesOwner,
      @NonNull List<Issue> issuesForMerge
  ) {
    ProjectEntity issuesOwnerEntity = projectRepository.findOneForShare(issuesOwner.getId())
        .orElseThrow(); // TODO: handle the exception

    var existedEntities = issueRepository.findAllForUpdateBy(issuesOwnerEntity.getId());

    // Splitting the project entities for deletion (TRUE) and update (FALSE).
    var map = splitIssuesEntitiesForDeleteAndMerge(
        existedEntities,
        issuesForMerge.stream().mapToLong(Issue::getRemoteId).toArray()
    );

    // Deleting the entities.
    deleteIssueEntities(map.get(true));

    // Save the entities.
    return saveIssueEntities(issuesForMerge, map.get(false), issuesOwnerEntity);
  }

  /**
   * Splits the issue entities for deletion (TRUE) and update (FALSE).
   */
  private Map<Boolean, List<IssueEntity>> splitIssuesEntitiesForDeleteAndMerge(
      @NonNull List<IssueEntity> existedEntities,
      @NonNull long[] remoteIds
  ) {
    return existedEntities.stream()
        .collect(partitioningBy(
            issueEntity -> LongStream.of(remoteIds)
                .noneMatch(remoteId -> remoteId == issueEntity.getRemoteId())
        ));
  }

  private void deleteIssueEntities(List<IssueEntity> issueEntities) {
    issueRepository.deleteAll(issueEntities);
  }

  private Stream<Issue> saveIssueEntities(
      @NonNull List<Issue> issuesForSave,
      @NonNull List<IssueEntity> existedEntities,
      @NonNull ProjectEntity issuesOwnerEntity
  ) {
    List<Issue> mergedSuperGroups = new ArrayList<>(issuesForSave.size());

    var map = splitIssuesForCreateAndUpdate(
        issuesForSave,
        existedEntities.stream().mapToLong(IssueEntity::getRemoteId).toArray()
    );

    // Creating entities.
    mergedSuperGroups.addAll(
        createIssues(map.get(true), issuesOwnerEntity).collect(toList())
    );

    // Updating entities.
    mergedSuperGroups.addAll(
        updateIssues(existedEntities, map.get(false)).collect(toList())
    );

    return mergedSuperGroups.stream();
  }

  /**
   * Splits the issue models for create (TRUE) and update (FALSE).
   */
  private Map<Boolean, List<Issue>> splitIssuesForCreateAndUpdate(
      @NonNull List<Issue> modelsForSave,
      @NonNull long[] existedRemoteIds
  ) {
    return modelsForSave.stream()
        .collect(partitioningBy(
            issue -> LongStream.of(existedRemoteIds)
                .noneMatch(remoteId -> remoteId == issue.getRemoteId())
        ));
  }

  private Stream<Issue> createIssues(
      @NonNull Collection<Issue> issues,
      @NonNull ProjectEntity issuesOwnerEntity
  ) {
    return issueRepository
        .saveAll(
            issues.stream()
                .map(issue -> createIssueEntity(issue, issuesOwnerEntity))
                .collect(toList())
        )
        .stream()
        .map(IssueFactory::createIssue);
  }

  private Stream<Issue> updateIssues(
      @NonNull List<IssueEntity> existedEntities,
      @NonNull List<Issue> issuesForUpdate
  ) {
    return mapProjects(issuesForUpdate, existedEntities)
        .entrySet()
        .stream()
        .map((entry) -> {
          var projectEntity = entry.getValue().orElseThrow();
          mergeIssueEntity(entry.getKey(), projectEntity);
          return createIssue(issueRepository.save(projectEntity));
        });
  }

  /**
   * <p>Maps issues and the related issue entities by {@link Issue#getRemoteId()}.</p>
   */
  private Map<Issue, Optional<IssueEntity>> mapProjects(
      @NonNull List<Issue> projects,
      @NonNull List<IssueEntity> issueEntities
  ) {
    return projects.stream()
        .collect(toMap(
            issue -> issue,
            issue ->
                issueEntities.stream()
                    .filter(entity -> issue.getRemoteId() == entity.getRemoteId())
                    .findAny()
        ));
  }
}
