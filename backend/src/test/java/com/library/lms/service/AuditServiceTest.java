package com.library.lms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.CrudRepository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.library.lms.dto.AuditEventResponse;
import com.library.lms.dto.PagedResponse;
import com.library.lms.entity.AuditAction;
import com.library.lms.entity.AuditEvent;
import com.library.lms.entity.AuditOutcome;
import com.library.lms.entity.AuditTargetType;
import com.library.lms.entity.Library;
import com.library.lms.entity.Role;
import com.library.lms.entity.User;
import com.library.lms.exception.AuditAccessDeniedException;
import com.library.lms.exception.InvalidPaginationException;
import com.library.lms.exception.InvalidSortException;
import com.library.lms.repository.AuditEventRepository;
import com.library.lms.repository.LibraryRepository;
import com.library.lms.repository.UserRepository;

/**
 * The audit boundary's own rules, with no database: what an event carries,
 * which transaction each kind of event runs in, why none can hold a secret,
 * and who may read them back.
 */
class AuditServiceTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-09-19T06:30:00Z"), ZoneId.of("Asia/Kolkata"));

    private final AuditEventRepository events = mock(AuditEventRepository.class);

    private final LibraryRepository libraries = mock(LibraryRepository.class);

    private final UserRepository users = mock(UserRepository.class);

    private final AuditService service = new AuditService(events, libraries, users, FIXED);

    private AuditEvent saved() {
        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(events).save(event.capture());
        return event.getValue();
    }

    private Library library(long id) {
        Library library = new Library();
        library.setId(id);
        when(libraries.getReferenceById(id)).thenReturn(library);
        return library;
    }

    // ---------- what an event carries ----------

    @Test
    void aSuccessCarriesItsLibraryActorActionTargetAndTime() {
        Library library = library(7L);

        service.recordSuccess(AuditAction.USER_CREATED, 7L, 11L, AuditTarget.user(42L));

        AuditEvent event = saved();
        assertThat(event.getLibrary()).isSameAs(library);
        assertThat(event.getActorUserId()).isEqualTo(11L);
        assertThat(event.getAction()).isEqualTo(AuditAction.USER_CREATED);
        assertThat(event.getTargetType()).isEqualTo(AuditTargetType.USER);
        assertThat(event.getTargetId()).isEqualTo(42L);
        assertThat(event.getOutcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(event.getOccurredAt()).isEqualTo(LocalDateTime.now(FIXED));
    }

    @Test
    void aFailureIsMarkedAsOneAndMayHaveNeitherActorNorTarget() {
        library(7L);

        service.recordFailure(AuditAction.PASSWORD_RESET_BY_STAFF, 7L, null, AuditTarget.none());

        AuditEvent event = saved();
        assertThat(event.getOutcome()).isEqualTo(AuditOutcome.FAILURE);
        assertThat(event.getActorUserId()).isNull();
        assertThat(event.getTargetType()).isNull();
        assertThat(event.getTargetId()).isNull();
    }

    @Test
    void aLibraryTargetIsNamedAsOne() {
        library(7L);

        service.recordSuccess(AuditAction.LIBRARY_CREATED, 7L, 11L, AuditTarget.library(8L));

        assertThat(saved().getTargetType()).isEqualTo(AuditTargetType.LIBRARY);
    }

    @Test
    void anEventWithoutALibraryActionOrTargetIsRefusedAndNothingIsSaved() {
        assertThatThrownBy(() -> service.recordSuccess(AuditAction.USER_CREATED, null, 11L, AuditTarget.none()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("library");
        assertThatThrownBy(() -> service.recordFailure(null, 7L, 11L, AuditTarget.none()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.recordSuccess(AuditAction.USER_CREATED, 7L, 11L, null))
                .isInstanceOf(IllegalArgumentException.class);

        verify(events, never()).save(any());
    }

    // ---------- which transaction each kind runs in ----------

    @Test
    void aSuccessMustJoinTheBusinessTransaction() throws Exception {
        Method success = AuditService.class.getMethod("recordSuccess", AuditAction.class, Long.class, Long.class,
                AuditTarget.class);

        assertThat(success.getAnnotation(Transactional.class).propagation())
                .as("committed with the change it describes, or rolled back with it - and never without one")
                .isEqualTo(Propagation.MANDATORY);
    }

    @Test
    void aFailureRunsInATransactionOfItsOwn() throws Exception {
        Method failure = AuditService.class.getMethod("recordFailure", AuditAction.class, Long.class, Long.class,
                AuditTarget.class);

        assertThat(failure.getAnnotation(Transactional.class).propagation())
                .as("so the refusal's rollback cannot erase the record of it")
                .isEqualTo(Propagation.REQUIRES_NEW);
    }

    // ---------- why an event cannot hold a secret ----------

    @Test
    void anEventHasNoFieldThatCouldHoldText() {
        List<Class<?>> types = new ArrayList<>();
        for (Field field : AuditEvent.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                types.add(field.getType());
            }
        }

        assertThat(types)
                .as("ids, enums, a library and a time - no String anywhere")
                .doesNotContain(String.class, char[].class, byte[].class)
                .allSatisfy(type -> assertThat(type == Long.class || type.isEnum() || type == LocalDateTime.class
                        || type == Library.class).as(type.getName()).isTrue());
    }

    @Test
    void theRecordingMethodsTakeNoText() {
        for (String name : List.of("recordSuccess", "recordFailure")) {
            Method method = Arrays.stream(AuditService.class.getMethods())
                    .filter(candidate -> candidate.getName().equals(name))
                    .findFirst()
                    .orElseThrow();

            assertThat(method.getParameterTypes()).as(name).doesNotContain(String.class);
        }
    }

    // ---------- reading the log ----------

    private User caller(String username, Role role, long libraryId) {
        Library library = new Library();
        library.setId(libraryId);

        User user = new User();
        user.setId(99L);
        user.setUsername(username);
        user.setRole(role);
        user.setLibrary(library);

        when(users.findByUsername(username)).thenReturn(Optional.of(user));
        return user;
    }

    /** Lets the query answer with the given page, whatever it is asked. */
    private void queryReturns(Page<AuditEvent> page) {
        when(events.findByLibraryIdMatching(any(), any(), any(), any(), any(), any(), any(), any(),
                any(Pageable.class))).thenReturn(page);
    }

    private Pageable capturedPageable() {
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(events).findByLibraryIdMatching(any(), any(), any(), any(), any(), any(), any(), any(),
                pageable.capture());
        return pageable.getValue();
    }

    @Test
    void onlyAnAdministratorMayReadTheLog() {
        for (Role role : List.of(Role.ROLE_LIBRARIAN, Role.ROLE_MEMBER)) {
            caller("staff-" + role, role, 7L);

            assertThatThrownBy(() -> service.findEvents(0, 10, "occurredAt", "desc",
                    AuditEventFilter.none(), "staff-" + role))
                    .as(role.name())
                    .isInstanceOf(AuditAccessDeniedException.class);
        }

        verify(events, never()).findByLibraryIdMatching(any(), any(), any(), any(), any(), any(), any(), any(),
                any(Pageable.class));
    }

    @Test
    void theQueryIsScopedToTheCallersOwnLibrary() {
        caller("admin", Role.ROLE_ADMIN, 7L);
        queryReturns(Page.empty());

        service.findEvents(0, 10, "occurredAt", "desc", AuditEventFilter.none(), "admin");

        ArgumentCaptor<Long> libraryId = ArgumentCaptor.forClass(Long.class);
        verify(events).findByLibraryIdMatching(libraryId.capture(), any(), any(), any(), any(), any(), any(), any(),
                any(Pageable.class));
        assertThat(libraryId.getValue()).as("the caller's library, never one from the request").isEqualTo(7L);
    }

    @Test
    void everyFilterReachesTheQueryUnchanged() {
        caller("admin", Role.ROLE_ADMIN, 7L);
        queryReturns(Page.empty());
        LocalDateTime from = LocalDateTime.now(FIXED).minusDays(1);
        LocalDateTime to = LocalDateTime.now(FIXED);

        service.findEvents(0, 10, "occurredAt", "desc", new AuditEventFilter(AuditAction.PASSWORD_CHANGED,
                AuditOutcome.FAILURE, 11L, AuditTargetType.USER, 42L, from, to), "admin");

        verify(events).findByLibraryIdMatching(eq(7L), eq(AuditAction.PASSWORD_CHANGED), eq(AuditOutcome.FAILURE),
                eq(11L), eq(AuditTargetType.USER), eq(42L), eq(from), eq(to), any(Pageable.class));
    }

    @Test
    void theNewestEventComesFirstWithTheIdBreakingTies() {
        caller("admin", Role.ROLE_ADMIN, 7L);
        queryReturns(Page.empty());

        service.findEvents(0, 10, "occurredAt", "desc", AuditEventFilter.none(), "admin");

        assertThat(capturedPageable().getSort())
                .containsExactly(Sort.Order.desc("occurredAt"), Sort.Order.desc("id"));
    }

    @Test
    void aPageIsRefusedWhenItIsNegativeEmptyOrTooWide() {
        caller("admin", Role.ROLE_ADMIN, 7L);

        for (int[] pageAndSize : List.of(new int[] { -1, 10 }, new int[] { 0, 0 }, new int[] { 0, 51 })) {
            assertThatThrownBy(() -> service.findEvents(pageAndSize[0], pageAndSize[1], "occurredAt", "desc",
                    AuditEventFilter.none(), "admin"))
                    .as("page %d size %d", pageAndSize[0], pageAndSize[1])
                    .isInstanceOf(InvalidPaginationException.class);
        }
    }

    @Test
    void onlyTheTimeAndTheIdCanBeSortedOn() {
        caller("admin", Role.ROLE_ADMIN, 7L);

        for (String field : List.of("actorUserId", "action", "library", "password")) {
            assertThatThrownBy(() -> service.findEvents(0, 10, field, "desc", AuditEventFilter.none(), "admin"))
                    .as(field)
                    .isInstanceOf(InvalidSortException.class);
        }

        assertThatThrownBy(() -> service.findEvents(0, 10, "occurredAt", "sideways", AuditEventFilter.none(), "admin"))
                .isInstanceOf(InvalidSortException.class);
    }

    @Test
    void anEventIsReadBackAsIdsNamesAndATimeOnly() {
        caller("admin", Role.ROLE_ADMIN, 7L);
        Library library = new Library();
        library.setId(7L);
        LocalDateTime occurredAt = LocalDateTime.now(FIXED);
        AuditEvent event = new AuditEvent(library, 11L, AuditAction.USER_CREATED, AuditTargetType.USER, 42L,
                AuditOutcome.SUCCESS, occurredAt);
        queryReturns(new PageImpl<>(List.of(event), PageRequest.of(0, 10), 1));

        PagedResponse<AuditEventResponse> response = service.findEvents(0, 10, "occurredAt", "desc",
                AuditEventFilter.none(), "admin");

        assertThat(response.getTotalElements()).isEqualTo(1);
        AuditEventResponse read = response.getContent().get(0);
        assertThat(read.action()).isEqualTo(AuditAction.USER_CREATED);
        assertThat(read.outcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(read.actorUserId()).isEqualTo(11L);
        assertThat(read.targetType()).isEqualTo(AuditTargetType.USER);
        assertThat(read.targetId()).isEqualTo(42L);
        assertThat(read.occurredAt()).isEqualTo(occurredAt);
        assertThat(Arrays.stream(AuditEventResponse.class.getRecordComponents()).map(RecordComponent::getName))
                .as("the library is not repeated, and nothing else is added")
                .containsExactlyInAnyOrder("id", "action", "outcome", "actorUserId", "targetType", "targetId",
                        "occurredAt");
    }

    @Test
    void readingTheLogRecordsNothing() {
        caller("admin", Role.ROLE_ADMIN, 7L);
        queryReturns(Page.empty());

        service.findEvents(0, 10, "occurredAt", "desc", AuditEventFilter.none(), "admin");

        verify(events, never()).save(any());
    }

    @Test
    void theRepositoryCanAppendButNeverChangeOrRemove() {
        assertThat(CrudRepository.class.isAssignableFrom(AuditEventRepository.class))
                .as("no inherited delete, update or unscoped findAll")
                .isFalse();
        assertThat(Arrays.stream(AuditEventRepository.class.getDeclaredMethods()).map(Method::getName))
                .allMatch(name -> name.equals("save") || name.startsWith("findByLibraryId"));
    }
}
