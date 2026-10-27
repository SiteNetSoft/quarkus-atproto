package org.sitenetsoft.quarkus.atproto.xrpc.deployment;

import org.sitenetsoft.quarkus.atproto.lexicon.BlobRef;
import org.sitenetsoft.quarkus.atproto.lexicon.CidLink;
import org.sitenetsoft.quarkus.atproto.xrpc.BlockingXrpcClient;
import org.sitenetsoft.quarkus.atproto.xrpc.DefaultWriteGuard;
import org.sitenetsoft.quarkus.atproto.xrpc.RecordsImpl;
import org.sitenetsoft.quarkus.atproto.xrpc.WriteGuard;
import org.sitenetsoft.quarkus.atproto.xrpc.XrpcClientImpl;
import org.sitenetsoft.quarkus.atproto.xrpc.api.ReactiveXrpcClient;
import org.sitenetsoft.quarkus.atproto.xrpc.api.XrpcClient;

import com.atproto.repo.CreateRecord;
import com.atproto.repo.Defs;
import com.atproto.repo.DeleteRecord;
import com.atproto.repo.GetRecord;
import com.atproto.repo.PutRecord;
import com.atproto.repo.UploadBlob;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;

class AtprotoXrpcProcessor {

    static final String FEATURE = "atproto-xrpc";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    AdditionalBeanBuildItem beans() {
        return AdditionalBeanBuildItem.builder()
                .addBeanClasses(XrpcClientImpl.class, BlockingXrpcClient.class, RecordsImpl.class,
                        DefaultWriteGuard.class)
                .setUnremovable()
                .build();
    }

    @BuildStep
    UnremovableBeanBuildItem unremovableSpis() {
        return UnremovableBeanBuildItem.beanTypes(ReactiveXrpcClient.class, XrpcClient.class, WriteGuard.class);
    }

    /** The {@code com.atproto.repo} wire types the records API reads and writes with Jackson. */
    @BuildStep
    ReflectiveClassBuildItem recordsWireTypes() {
        return ReflectiveClassBuildItem.builder(CreateRecord.Input.class, CreateRecord.Output.class,
                PutRecord.Input.class, PutRecord.Output.class, GetRecord.Output.class, DeleteRecord.Input.class,
                DeleteRecord.Output.class, UploadBlob.Output.class, Defs.CommitMeta.class, BlobRef.class,
                CidLink.class)
                .constructors().methods().fields().build();
    }
}
