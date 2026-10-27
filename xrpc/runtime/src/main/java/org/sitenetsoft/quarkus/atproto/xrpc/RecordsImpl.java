package org.sitenetsoft.quarkus.atproto.xrpc;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.sitenetsoft.atproto.syntax.Cid;
import org.sitenetsoft.atproto.syntax.Did;
import org.sitenetsoft.atproto.syntax.Nsid;
import org.sitenetsoft.atproto.syntax.RecordKey;
import org.sitenetsoft.quarkus.atproto.lexicon.BlobRef;
import org.sitenetsoft.quarkus.atproto.lexicon.LexiconValidator;
import org.sitenetsoft.quarkus.atproto.lexicon.ValidationProblem;
import org.sitenetsoft.quarkus.atproto.xrpc.api.ReactiveXrpcClient;
import org.sitenetsoft.quarkus.atproto.xrpc.api.XrpcAuth;
import org.sitenetsoft.quarkus.atproto.xrpc.api.XrpcException;

import com.atproto.repo.CreateRecord;
import com.atproto.repo.DeleteRecord;
import com.atproto.repo.GetRecord;
import com.atproto.repo.PutRecord;
import com.atproto.repo.RepoClient;
import com.atproto.repo.UploadBlob;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.smallrye.mutiny.Uni;

/**
 * Makes the records API for one repository: {@link #forRepo} (reactive) and {@link #blockingForRepo}. The OAuth
 * extension calls it with the session's PDS and DPoP {@link XrpcAuth} (and, for scope checks, its own
 * {@link WriteGuard}); an application can call it with any other {@link XrpcAuth}.
 */
@ApplicationScoped
public class RecordsImpl {

  /** The collection a {@link WriteGuard} is shown for a blob upload. */
  public static final Nsid UPLOAD_BLOB = Nsid.parse(UploadBlob.NSID);
  static final String CREATE = "create";
  static final String UPDATE = "update";
  static final String DELETE = "delete";

  private final RepoClient repoClient;
  private final LexiconValidator validator;
  private final WriteGuard guard;
  private final ObjectMapper mapper;
  private final Duration limit;

  @Inject
  public RecordsImpl(ReactiveXrpcClient xrpc, LexiconValidator validator, WriteGuard guard, ObjectMapper mapper,
      XrpcConfig config) {
    this.repoClient = new RepoClient(xrpc);
    this.validator = validator;
    this.guard = guard;
    this.mapper = mapper;
    // create = one exchange, but the authorizer may refresh first: same bound as the blocking client
    this.limit = BlockingXrpcClient.awaitLimit(config);
  }

  /** The records of {@code repo} on {@code pds}, authorized by {@code auth}, writes checked by the {@link WriteGuard} bean. */
  public ReactiveAtprotoRecords forRepo(Did repo, URI pds, XrpcAuth auth) {
    return forRepo(repo, pds, auth, guard);
  }

  /** As {@link #forRepo(Did, URI, XrpcAuth)} with a specific guard (a session's granted scope). */
  public ReactiveAtprotoRecords forRepo(Did repo, URI pds, XrpcAuth auth, WriteGuard guard) {
    Objects.requireNonNull(pds, "pds");
    return forRepo(repo, () -> Uni.createFrom().item(pds), auth, guard);
  }

  /**
   * As {@link #forRepo(Did, URI, XrpcAuth, WriteGuard)} with the PDS looked up for <b>each call</b> (after the
   * record is checked, before it is sent): a long-lived view follows an account that moved PDS.
   */
  public ReactiveAtprotoRecords forRepo(Did repo, Supplier<Uni<URI>> pds, XrpcAuth auth, WriteGuard guard) {
    return new Bound(Objects.requireNonNull(repo, "repo"), Objects.requireNonNull(pds, "pds"),
        Optional.of(Objects.requireNonNull(auth, "auth")), Objects.requireNonNull(guard, "guard"));
  }

  /** Blocking form of {@link #forRepo(Did, Supplier, XrpcAuth, WriteGuard)}. */
  public AtprotoRecords blockingForRepo(Did repo, Supplier<Uni<URI>> pds, XrpcAuth auth, WriteGuard guard) {
    return blocking(forRepo(repo, pds, auth, guard));
  }

  public AtprotoRecords blockingForRepo(Did repo, URI pds, XrpcAuth auth) {
    return blocking(forRepo(repo, pds, auth));
  }

  public AtprotoRecords blockingForRepo(Did repo, URI pds, XrpcAuth auth, WriteGuard guard) {
    return blocking(forRepo(repo, pds, auth, guard));
  }

  /** A blocking view of {@code records}, bounded by the XRPC timeout. */
  public AtprotoRecords blocking(ReactiveAtprotoRecords records) {
    return new Blocking(Objects.requireNonNull(records, "records"), limit);
  }

  private final class Bound implements ReactiveAtprotoRecords {
    private final Did repo;
    private final Supplier<Uni<URI>> pds;
    private final Optional<XrpcAuth> auth;
    private final WriteGuard guard;

    Bound(Did repo, Supplier<Uni<URI>> pds, Optional<XrpcAuth> auth, WriteGuard guard) {
      this.repo = repo;
      this.pds = pds;
      this.auth = auth;
      this.guard = guard;
    }

    /** Where this call goes. */
    private Uni<URI> pds() {
      return Uni.createFrom().deferred(() -> Objects.requireNonNull(pds.get(), "pds"))
          .map(p -> Objects.requireNonNull(p, "pds"));
    }

    @Override
    public Uni<RecordResult> create(Nsid collection, Object record) {
      return createRecord(collection, null, record);
    }

    @Override
    public Uni<RecordResult> create(Nsid collection, RecordKey rkey, Object record) {
      return Uni.createFrom().deferred(() -> createRecord(collection, Objects.requireNonNull(rkey, "rkey"), record));
    }

    private Uni<RecordResult> createRecord(Nsid collection, RecordKey rkey, Object record) {
      return prepare(collection, rkey, CREATE, record)
          .chain(tree -> pds().chain(p -> repoClient.createRecord(p,
              new CreateRecord.Input(repo, collection, rkey, null, tree, null), auth)))
          .map(o -> new RecordResult(o.uri(), o.cid(), Optional.ofNullable(o.validationStatus())));
    }

    @Override
    public Uni<RecordResult> put(Nsid collection, RecordKey rkey, Object record, Optional<Cid> swapRecord) {
      return Uni.createFrom().deferred(() -> {
        Objects.requireNonNull(rkey, "rkey");
        Cid swap = swapRecord == null ? null : swapRecord.orElse(null);
        return prepare(collection, rkey, UPDATE, record)
            .chain(tree -> pds().chain(p -> repoClient.putRecord(p,
                new PutRecord.Input(repo, collection, rkey, null, tree, swap, null), auth)))
            .map(o -> new RecordResult(o.uri(), o.cid(), Optional.ofNullable(o.validationStatus())));
      });
    }

    @Override
    public <T> Uni<Optional<T>> get(Nsid collection, RecordKey rkey, Class<T> type) {
      return Uni.createFrom().deferred(() -> {
        Objects.requireNonNull(type, "type");
        return pds().chain(p -> repoClient.getRecord(p, new GetRecord.Params(repo, collection, rkey, null), auth))
            .map(o -> Optional.of(convert(o, type)))
            .onFailure(e -> e instanceof XrpcException x && GetRecord.ERROR_RECORD_NOT_FOUND.equals(x.error()))
            .recoverWithItem(Optional.empty());
      });
    }

    private <T> T convert(GetRecord.Output out, Class<T> type) {
      if (type.isInstance(out.value())) {
        return type.cast(out.value());
      }
      try {
        return mapper.treeToValue(out.value(), type);
      } catch (JsonProcessingException e) {
        throw new IllegalStateException("The record at " + out.uri() + " does not map to " + type.getName() + ": "
            + e.getOriginalMessage(), e);
      }
    }

    @Override
    public Uni<Void> delete(Nsid collection, RecordKey rkey) {
      return Uni.createFrom().deferred(() -> {
        Objects.requireNonNull(collection, "collection");
        Objects.requireNonNull(rkey, "rkey");
        guard.check(collection, DELETE, Optional.empty());
        return pds().chain(p -> repoClient.deleteRecord(p, new DeleteRecord.Input(repo, collection, rkey, null, null),
            auth)).replaceWithVoid();
      });
    }

    @Override
    public Uni<BlobRef> uploadBlob(byte[] data, String mimeType) {
      return Uni.createFrom().deferred(() -> {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(mimeType, "mimeType");
        guard.check(UPLOAD_BLOB, CREATE, Optional.of(mimeType));
        return pds().chain(p -> repoClient.uploadBlob(p, data, mimeType, auth)).map(UploadBlob.Output::blob);
      });
    }

    /**
     * Guard, then the record as JSON with its {@code $type}, then validation of the record and of the record key
     * ({@code rkey}, when the caller chose one) against the Lexicon's key type; all before anything is sent.
     */
    private Uni<JsonNode> prepare(Nsid collection, RecordKey rkey, String action, Object record) {
      return Uni.createFrom().item(() -> {
        Objects.requireNonNull(collection, "collection");
        Objects.requireNonNull(record, "record");
        guard.check(collection, action, Optional.empty());
        ObjectNode tree = typed(collection, record);
        List<ValidationProblem> problems = new ArrayList<>(validator.validate(collection, tree));
        if (rkey != null) {
          validator.validateKey(collection, rkey).stream().filter(p -> !problems.contains(p)).forEach(problems::add);
        }
        if (!problems.isEmpty()) {
          throw new RecordValidationException(collection, problems);
        }
        return tree;
      });
    }

    private ObjectNode typed(Nsid collection, Object record) {
      JsonNode node = record instanceof JsonNode n ? n : mapper.valueToTree(record);
      if (!(node instanceof ObjectNode object)) {
        throw new RecordValidationException(collection,
            List.of(new ValidationProblem("/", "a record must be a JSON object")));
      }
      JsonNode type = object.get("$type");
      if (type == null || type.isNull()) {
        ObjectNode withType = mapper.createObjectNode().put("$type", collection.toString());
        object.properties().forEach(e -> {
          if (!e.getKey().equals("$type")) {
            withType.set(e.getKey(), e.getValue());
          }
        });
        return withType;
      }
      if (!type.isTextual() || !type.asText().equals(collection.toString())) {
        throw new RecordValidationException(collection, List.of(new ValidationProblem("/$type",
            "the record's $type " + type + " is not the collection " + collection)));
      }
      return object;
    }
  }

  private static final class Blocking implements AtprotoRecords {
    private final ReactiveAtprotoRecords delegate;
    private final Duration limit;

    Blocking(ReactiveAtprotoRecords delegate, Duration limit) {
      this.delegate = delegate;
      this.limit = limit;
    }

    @Override
    public RecordResult create(Nsid collection, Object record) {
      return BlockingXrpcClient.await(collection, delegate.create(collection, record), limit);
    }

    @Override
    public RecordResult create(Nsid collection, RecordKey rkey, Object record) {
      return BlockingXrpcClient.await(collection, delegate.create(collection, rkey, record), limit);
    }

    @Override
    public RecordResult put(Nsid collection, RecordKey rkey, Object record, Optional<Cid> swapRecord) {
      return BlockingXrpcClient.await(collection, delegate.put(collection, rkey, record, swapRecord), limit);
    }

    @Override
    public <T> Optional<T> get(Nsid collection, RecordKey rkey, Class<T> type) {
      return BlockingXrpcClient.await(collection, delegate.get(collection, rkey, type), limit);
    }

    @Override
    public void delete(Nsid collection, RecordKey rkey) {
      BlockingXrpcClient.await(collection, delegate.delete(collection, rkey), limit);
    }

    @Override
    public BlobRef uploadBlob(byte[] data, String mimeType) {
      return BlockingXrpcClient.await("uploadBlob", delegate.uploadBlob(data, mimeType), limit);
    }
  }
}
